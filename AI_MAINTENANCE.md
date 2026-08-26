# AI 接手维护指南（CloudBox）

> **本文件是 AI 接手的必读文档。** 任何 AI（或人类）接手本项目的开发/维护，
> 请先完整阅读本文件 + `README.md`，再动手改代码。
> 本文档最后更新：2026-08-26（对应 commit 8806d6c7 之后的代码状态）。

---

## 0. 项目一句话

CloudBox（App 名"云匣"，包名 `com.cloudbox.app`）是蓝奏云网盘的第三方 Android 客户端，
通过模拟蓝奏云 Web 端（woozooo 域名体系）的 HTTP 接口实现全部功能，
绕过官方 App 对非会员手机端隐藏 APK 等格式下载入口的限制。
**仅供个人学习使用。**

---

## 1. 技术栈与版本（严格锁定，升级需谨慎）

| 组件 | 版本 | 备注 |
|---|---|---|
| Kotlin | 2.0.20 | Compose 编译器随 Kotlin 版本（`kotlin-compose` 插件） |
| AGP | **8.7.3** | ⚠️ 不要降回 8.5.x：其内置 R8 有 `ConcurrentModificationException` bug |
| Gradle | 8.9 | 仓库无 gradle-wrapper.jar（沙箱无法生成二进制），CI 用 `gradle CLI 8.9` |
| Compose BOM | 2024.09.03 | material3 1.3.0（含 `pulltorefresh.PullToRefreshBox`） |
| Hilt | 2.52 + KSP 2.0.20-1.0.25 | 含 `hilt-work`（UploadWorker 注入） |
| Retrofit / OkHttp | 2.11.0 / 4.12.0 | OkHttp 4.x 的 `HttpUrl.get(String)` 是 **ERROR 级废弃**，必须用 `toHttpUrl()` |
| Room / DataStore | 2.6.1 / 1.1.1 | Room 用 `fallbackToDestructiveMigration`（自用项目） |
| security-crypto | 1.1.0-alpha06 | EncryptedSharedPreferences（Keystore 加密） |
| WorkManager | 2.9.1 | 需要 Hilt WorkerFactory（见 §7.5） |
| Zip4j | 2.11.5 | 分卷压缩 |
| ZXing / Jsoup | 3.5.3 / 1.18.1 | 二维码 / HTML 解析 |
| minSdk / targetSdk | 26 / 34 | JDK 17 |

---

## 2. 目录结构导航

```
app/src/main/java/com/cloudbox/app/
├── CloudBoxApp.kt              # Application：Hilt + WorkManager 配置 + 启动会话自愈
├── MainActivity.kt             # 导航宿主（Routes 常量在此） + 剪贴板监听启动
├── common/                     # 常量/工具（无 Android 依赖的纯逻辑）
│   ├── AppConstants.kt         # ⭐ UA、域名黑名单、正则、超时/重试/风控常量
│   ├── HtmlExtractor.kt        # ⭐ t/k/sign/formhash/uid 正则提取（接口易变，改这里）
│   ├── ApiError.kt             # 统一错误分类（403=Cookie过期/429=风控/网络/服务器）
│   ├── DomainUtils.kt          # 域名规范化/黑名单/分享ID提取
│   ├── ClipboardLinkWatcher.kt # 剪贴板链接识别（Android 10+ 回调 + 低版本轮询）
│   ├── SplitZipUtil.kt         # Zip4j 分卷（95MB/卷 .zip/.z01/.z02）
│   ├── QrCodeUtil.kt           # ZXing BitMatrix→Bitmap
│   └── DownloadHelper.kt       # 下载完成处理（APK 安装引导）
├── core/
│   ├── domain/                 # 领域层（纯 Kotlin，无 Android 依赖）
│   │   ├── model/              # LanzouDomainConfig / CloudFile / ShareInfo / DirectLink ...
│   │   └── repository/         # ⭐ 接口定义（DomainRepository/AuthRepository/FileRepository/
│   │                           #   UploadRepository/DownloadRepository/ShareRepository/
│   │                           #   DirectLinkRepository/SearchRepository）
│   ├── data/                   # 数据层
│   │   ├── remote/
│   │   │   ├── LanzouApiService.kt    # ⭐ Retrofit 全部接口定义（task 编号见 §5 速查表）
│   │   │   ├── LanzouDomainInterceptor.kt # ⭐⭐ 域名动态重写（核心机制，见 §4.1）
│   │   │   ├── UserAgentInterceptor.kt    # 桌面 UA 伪装（全程必须）
│   │   │   ├── RetryInterceptor.kt        # 指数退避（2s/4s/8s + 抖动，403 不重试）
│   │   │   ├── CookiePersistenceJar.kt    # ⭐ Cookie 持久化（多账号槽位）
│   │   │   ├── RemoteDomainSource.kt      # Gist JSON 远程域名配置
│   │   │   └── LanzouApiClient.kt         # OkHttp/Retrofit 单例组装
│   │   ├── local/
│   │   │   ├── db/             # Room：FileCache/SearchIndex/DownloadRecord/DirectLink/Favorite
│   │   │   ├── datastore/      # DomainConfigStore / SettingsStore
│   │   │   └── secure/         # AccountSecureStore（EncryptedSharedPreferences）
│   │   └── repository/         # 各 Impl（业务逻辑主要在这里）
│   │       ├── FileRepositoryImpl.kt      # 列表 + 管理操作 + 回收站（mydisk.php HTML）
│   │       ├── DirectLinkRepositoryImpl.kt # ⭐⭐ 直链解析核心（见 §4.4）
│   │       ├── UploadRepositoryImpl.kt     # 上传 + 分卷 + 延时防封
│   │       ├── SearchRepositoryImpl.kt     # 全盘索引 + LIKE 搜索
│   │       └── ...（Auth/Domain/Download/Share）
│   └── di/                     # NetworkModule / RepositoryModule / DatabaseModule
└── feature/                    # UI 层（每功能一包，ViewModel + Screen + Dialog）
    ├── login/ main/ filelist/ upload/ download/ resolve/ search/ settings/ favorites/ recycle/ domain/
```

---

## 3. 数据流总览

```
登录(login) → Cookie 存入 EncryptedSharedPreferences（多账号槽位）
   ↓
主界面(main) 底部 4 Tab：
  网盘(filelist)：面包屑浏览 → task=47 取文件夹 + task=5 取文件（分页）
      ├─ 长按多选 → 删除(task=6/3)/移动(task=20)/分享(task=22/18)
      ├─ 新建文件夹(task=2)/重命名(task=4/46)/提取码(task=23/16)/描述(task=11)
      └─ 回收站(mydisk.php + formhash)
  解析(resolve)：分享链接 → 直链解析（GET分享页→sign→ajaxm.php→dom+/file/+url）→ 下载
  上传(upload)：SAF 选文件 → WorkManager 队列 → fileup.php；>100MB 自动分卷
  我的(me)：下载管理/收藏夹/回收站/设置（域名/UA/多账号/Cookie/深色模式）
```

---

## 4. 核心机制详解（改代码前必读）

### 4.1 域名动态重写（⭐⭐ 项目根基）

**问题**：蓝奏云域名频繁漂移（lanzous→lanzou→lanzoux→lanzoui→lanzoup→lanzouu→lanzouo→lanzouh），
且支持远程更新 + 用户手动覆盖。Retrofit 创建时绑定 baseUrl 无法热更新。

**方案**：所有 Retrofit 请求的 baseUrl 用占位 host `lz.dynamic.invalid`
（`AppConstants.PLACEHOLDER_HOST`），由 `LanzouDomainInterceptor` 在请求发出前
按 URL 路径角色重写到当前配置的真实域名：

| 路径特征 | 映射域 |
|---|---|
| 含 `/fileup.php` | uploadServer |
| 含 `ajaxm` | shareBase（直链解析部署在分享域） |
| 以 `.php` 结尾（doupload/mydisk/filemoreajax/login） | diskMain |
| 其他（分享页 HTML） | shareBase |

- 只有 host 等于占位符的请求才被重写；直链下载请求（动态 dom）不受影响。
- 当前配置由 `DomainRepositoryImpl` 合并（本地覆盖 > 远程 JSON > 内置默认），
  通过 `bindConfigFlow` 喂给拦截器（`AtomicReference` 同步缓存，网络线程无锁读取）。
- `lanzous.com` 在 `AppConstants.FORBIDDEN_DOMAINS` 黑名单（被第三方抢注，解析到不良站点），
  任何来源（含远程配置）都会被过滤。

### 4.2 Cookie 机制（登录态核心）

- `CookiePersistenceJar`：拦截 Set-Cookie → 按 `host` 分桶内存缓存 → 持久化到
  `EncryptedSharedPreferences`（key=`cookies_<uid>`，多账号独立槽位）。
- Cookie 的 domain 常带前导点（`.lanzou.com`），读取时按"host 等于 key 或 host 是 key 子域"匹配。
- 手动导入的纯键值对（无 Domain 属性）：按名字约定归属
  `phpdisk_info/ylogin → woozooo.com`，其他 → `lanzou.com`（否则请求不带 Cookie）。
- 登录成功判定 = CookieJar 中存在 `phpdisk_info`（login.php 成败都返回 200，
  唯一可靠信号是 Set-Cookie；失败页面 HTML 含中文错误文案，用 Jsoup 提取）。
- 有效期：phpdisk_info 约 20 天；`ensureSession()` 在启动时检测 lastActiveAt，
  **超过 18 天**（留 2 天缓冲）用保存的账密静默重登，失败清 Cookie 提示重登。

### 4.3 文件列表接口（与旧教程的差异，⚠️ 重要）

需求规格最初要求 `lx/fid/uid/t/k/up/ls` 参数方案，但经 LanZouCloud-API（2025 活跃维护）源码验证：
- **登录态列表**：`doupload.php` `task=5`，仅需 `folder_id + pg`（pg 翻页，响应 `info=0` 结束）。
  **不使用 t/k/up/ls/rep**（这些是旧教程方案，只用于分享页场景）。
- **子文件夹**：`doupload.php?uid=<uid>` `task=47`，`folder_id`。
- **t/k 参数只用于分享页** `filemoreajax.php`（`getShareFileList`，文件夹分享递归解析用），
  每次从分享页 HTML 实时提取（`HtmlExtractor.extractT/extractK`），禁止缓存复用。

### 4.4 直链解析流程（⭐⭐ 严格按此顺序）

```
resolve(shareUrl, password):
  1. 查 Room 缓存（TTL 1h，key=shareUrl 或 shareUrl|pwd=xxx）
  2. 若设置了第三方解析服务 URL → POST {url, pwd} 期望返回 {"url": 直链}，失败回落内置
  3. 内置：
     a. GET 分享页 https://{shareBase}/{shareId}（桌面 UA + Referer + Accept-Language zh-CN）
     b. 若页面含 acw_sc__v2 → 计算反爬 cookie 重试（AcwScV2，社区逆向算法，可能失效）
     c. 提取 sign（HtmlExtractor.extractSign，三形态依次尝试）
        无提取码分支：先取 iframe 地址 → GET iframe 页 → 再提取 sign
     d. POST {shareBase}/ajaxm.php：action=downprocess&sign&file_id&p&kd=1
     e. 响应 {"zt":1, "dom":域名, "url":路径, "inf":文件名}
     f. ⚠️ 直链 = dom + '/file/' + url（源码验证，不是 dom+url 直接拼！）
     g. 缓存入 Room
  4. 批量解析：每条间隔 1-3s 随机延时（风控：同 UA/IP 7 天同一分享页约 5 次，超限拉黑）
```

文件夹分享递归解析：GET 分享页提取 `t/k/fid` → 循环调 `filemoreajax.php`（lx=2, pg, k, t, fid, pwd）
直到 `zt=2`（取完；zt=3=提取码错误）→ 逐个解析文件直链。

### 4.5 上传与分卷（100MB 限制）

- 免费用户单文件上限 **100MB**（`AppConstants.FREE_FILE_LIMIT_BYTES`）。
  **不要写死任何"登录后放宽"逻辑**（会员额度 200M-210M 仅为社区传闻，无权威佐证）。
- 超限 → 自动分卷：Zip4j 切 **95MB/卷**（留余量），命名 `.zip / .z01 / .z02`
  （⚠️ `.001/.002` 会被蓝奏云拦截，必须用 zip 分卷命名）。
- 上传接口 `fileup.php` multipart：`task=1&vie=2&ve=2&id=WU_FILE_0&folder_id_bb_n=<folderId>&name=<文件名>` + `upload_file` 二进制。
  ⚠️ 参数名是 `folder_id_bb_n`（不是 `folder_id`）。
- 批量上传/分卷卷间：**1-3s 随机延时**（防触发风控封号，需求规格硬性要求）。
- 后缀伪装（设置可关）：exe/apk 等 → 改名 `.zip` 上传，下载侧还原后缀。
- 上传队列：WorkManager `UploadWorker`（Hilt 注入），进度经 `setProgress` 上报。

### 4.6 回收站（mydisk.php，HTML 交互）

- 非 JSON 接口！先 GET 对应 action 页提取 `formhash`（**每次现取，不可复用**——
  源码注释：此 formhash 与登录时不同），再 POST `mydisk.php?item=recycle` 表单执行。
- action/task 速查：`delete_all`(清空) / `restore_all`(恢复全部) /
  `file_restore|folder_restore`(恢复单项) / `file_delete_complete|folder_delete_complete`(彻底删除单项)。
- 成功判定：响应 HTML 含"清空回收站成功/恢复成功/删除成功"等关键词。

### 4.7 风控与 UA 伪装（全程遵守）

- 全程桌面 Chrome UA（手机 UA 会隐藏 APK 等下载入口）；UA 可在设置自定义。
- 所有批量操作（解析/上传/删除）都带 1-3s 随机延时。
- 403 = Cookie 过期（触发重登流程），429 = 风控限流（重试拦截器对 429/5xx 退避重试，403 不重试）。

---

## 5. 接口 task 编号速查表（LanZouCloud-API 源码逐字确认，commit 3bb917f）

| task | 接口 | 操作 | 表单参数 |
|---|---|---|---|
| 1 | fileup.php | 上传文件 | task, vie=2, ve=2, id=WU_FILE_0, folder_id_bb_n, name, upload_file |
| 2 | doupload.php | 新建文件夹 | parent_id(根=-1), folder_name, folder_description |
| 3 | doupload.php | 删除文件夹（入回收站） | folder_id |
| 4 | doupload.php | 重命名文件夹/改描述 | folder_id, folder_name, folder_description |
| 5 | doupload.php | 获取文件列表（登录态） | folder_id, pg |
| 6 | doupload.php | 删除文件（入回收站） | file_id |
| 11 | doupload.php | 设置文件描述 | file_id, desc（⚠️ 设置后不能清空） |
| 12 | doupload.php | 获取文件信息 | file_id → {text:名, info:描述} |
| 16 | doupload.php | 设置文件夹提取码 | folder_id, shows, shownames（0-12位） |
| 18 | doupload.php | 获取文件夹分享 | folder_id → info{new_url,name,pwd,onof} |
| 19 | doupload.php | 全部文件夹列表（移动选择） | file_id=-1 → info[{folder_id,folder_name}] |
| 20 | doupload.php | 移动文件 | file_id, folder_id(目标,根=-1) |
| 22 | doupload.php | 获取文件分享 | file_id → info{f_id,is_newd,pwd,onof}；链接=is_newd+'/'+f_id |
| 23 | doupload.php | 设置文件提取码 | file_id, shows, shownames（2-6位） |
| 46 | doupload.php | 重命名文件（会员功能） | file_id, file_name, type=2（不能改后缀） |
| 47 | doupload.php?uid= | 子文件夹列表 | folder_id → text[{fol_id,name,onof,folder_des}] |

- 登录：POST `login.php` task=3&uid&pwd（旧入口，部分账号可用；LanZouCloud-API 已转向 mydisk.php+formhash，本客户端以 login.php 为主 + Cookie 导入兜底）
- 回收站：mydisk.php（§4.6）
- 直链：ajaxm.php action=downprocess（§4.4）
- 分享页文件列表：filemoreajax.php lx/pg/k/t/fid/pwd

---

## 6. 已知限制与坑（改代码前必读）

### 6.1 业务限制
- Cookie 约 20 天过期（App 18 天后自动静默重登；改密/失败需手动重登）
- 域名可能随时漂移（打不开时去设置页换域或拉远程配置）
- 直链约 2 小时有效且绑定 Referer（缓存 1h；下载必须带 Referer 否则 403）
- 免费用户单文件 100MB（超限自动分卷 95MB）
- 非会员限制：文件重命名（task=46）可能失败、无法关闭提取码、文件描述不能清空
- 移动文件夹：官方无接口，客户端**仅支持文件移动**（UI 已注明）
- 中文搜索：Room FTS 对中文分词无效，用 LIKE 兜底（数据量大时慢，个人网盘够用）
- acw_sc__v2 反爬算法是社区逆向，蓝奏云更新后可能失效（届时自动跳过，可改用第三方解析）

### 6.2 工程坑（历史踩坑记录）
1. **AGP 必须 8.7.3+**：8.5.2 的 R8 有 `ConcurrentModificationException`（升级解决）
2. **release 关闭了 minify**（isMinifyEnabled=false）：R8 不稳定 + 自用项目不需要；proguard 规则保留
3. **gradle-wrapper.jar 缺失**：仓库不含（二进制无法经沙箱生成）；CI 用 `gradle CLI 8.9`（gradle/actions/setup-gradle），本地用 Android Studio 打开会自动处理
4. **WorkManager 需移除默认初始化器**：AndroidManifest 里有 `tools:node="remove"` 的 provider 配置（否则与 Hilt WorkerFactory 冲突，lint 强制）
5. **OkHttp 4.x `HttpUrl.get(String)` 是 ERROR 级废弃**：用 `"url".toHttpUrl()`
6. **GitHub 操作**：沙箱 git 443 通道不可用，必须走 api.github.com REST API；
   contents API **并发 PUT 会触发树冲突**（必须串行 + 已存在文件带 sha）
7. **GitHub Actions artifact 配额**：用完需 6-12h 刷新；本项目产物走 **Release 发布**（gh release create），不依赖 artifact
8. **CI workflow YAML**：含中文/多行参数的写法曾导致秒级解析失败，保持纯 ASCII 简单写法

---

## 7. 构建与发布

### 7.1 本地构建
```bash
# 环境：JDK 17 + Android SDK 34 + Gradle 8.9
export JAVA_HOME=<jdk17> ANDROID_HOME=<sdk>
gradle assembleDebug     # debug 包
gradle assembleRelease   # release 包（同一 keystore 签名）
```

### 7.2 签名（可覆盖安装的关键）
- keystore：`app/keystore/cloudbox-release.keystore`
- storePassword/keyPassword：`CloudBox@2026!`，keyAlias：`cloudbox`
- **debug 和 release 共用此 keystore**（build.gradle.kts signingConfigs），无 applicationId 后缀差异
- 指纹 SHA-256：`f3f6bbc6c413bfc880a61b2b4595600c8ad389327318639edf50baf82c3f5c3f`
- 结论：本地 debug / 本地 release / CI release 三路签名一致 → 互相覆盖安装
- 更换 keystore = 用户必须卸载重装（会破坏"签名一致"，非必要不动）

### 7.3 CI 发布流程（.github/workflows/build.yml）
```
push 到 main（或 workflow_dispatch）→
  checkout → JDK17 → Android SDK(platform-34) → gradle 8.9 →
  gradle assembleRelease → apksigner verify（验签）→
  gh release create v0.1.0-<时间戳>（APK 发布到 Release，--latest）
```
- Release 资产名固定 `app-release.apk`
- 每次 push 都会构建 + 发新 Release（tag 带时间戳，不覆盖旧版）

### 7.4 远程域名配置（Gist JSON 格式）
```json
{
  "loginEntry": "https://up.woozooo.com/",
  "diskMain": "https://pc.woozooo.com/",
  "shareBase": "https://www.lanzou.com/",
  "uploadServer": "https://pc.woozooo.com/",
  "fallbackDomains": ["https://www.lanzoui.com/", "https://www.lanzoup.com/",
                      "https://www.lanzoux.com/", "https://www.lanzouo.com/",
                      "https://www.lanzouh.com/"]
}
```

---

## 8. 常见修改指南（按场景）

| 需求 | 改哪里 |
|---|---|
| 蓝奏云接口参数变了 | `LanzouApiService.kt` + `HtmlExtractor.kt` + task 速查表；以 zaxtyson/LanZouCloud-API 最新源码为准交叉验证 |
| 域名又漂移了 | 无需改代码：App 设置页手动覆盖 / 远程 Gist 更新；若新域名出现则更新 `LanzouDomainConfig.DEFAULT` + `AppConstants.FORBIDDEN_DOMAINS` 检查 |
| 加新管理操作 | `LanzouApiService` 加方法（task 编号查 §5）→ `FileRepository` 接口 + `FileRepositoryImpl` 实现 → UI 调用 |
| 加新页面 | `feature/xxx/` 新包（ViewModel + Screen）→ `MainActivity` Routes 加路由 |
| 加新 Repository | `core/domain/repository` 接口 → `core/data/repository` Impl → `core/di/AppModule` 的 RepositoryModule 加 @Binds |
| 调整风控节奏 | `AppConstants.BATCH_DELAY_MIN/MAX_MS`、`MAX_RETRIES`、`RETRY_BASE_DELAY_MS` |
| 第三方解析服务 | 设置页填 URL，协议：POST `{url, pwd?}` → 响应 `{"url": 直链}` 或纯文本 URL |
| 上传格式伪装列表 | `UploadRepositoryImpl.needsSpoof()` 的扩展名集合 |

---

## 9. AI 接手第一步（Checklist）

1. ✅ 完整阅读本文件 + `README.md`
2. ✅ 拉取代码（https://github.com/d1667018881/cloudbox，私有，需 token）
3. ✅ 确认构建环境（JDK 17 / SDK 34 / Gradle 8.9 / AGP 8.7.3）
4. ✅ 本地跑一次 `assembleRelease` 验证基线
5. ✅ 对照 zaxtyson/LanZouCloud-API 最新源码复核 §5 的 task 表是否仍然有效
6. ✅ 核实域名池（§7.4）是否仍可用（被墙/抢注域名及时清理）
7. ⚠️ 改动前先看 §6 的坑列表；改动后跑完整构建 + apksigner 验签
8. ⚠️ 发布流程：push main 自动构建发 Release；**不要动 keystore**（动了就无法覆盖安装）
