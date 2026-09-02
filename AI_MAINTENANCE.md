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
- 登录成功判定 = CookieJar 中存在 `phpdisk_info`（账号中心接口成败都返回 200 + JSON zt 字段，
  但凭证仍以 Set-Cookie 到手为准；失败时 JSON msgs 直接给中文错误文案）。
  ⚠️ login.php（task=3）已于 2026-08-31 实测下线（pc/up.woozooo.com 双 404），
  现行登录协议见 §13。
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

### 7.1 开发流程（无需本地环境，改完直接 push）

**本项目不要求本地搭建 Android 开发环境**（JDK/SDK/Gradle 都不用装）。
标准开发流程：

```
修改代码 → git push 到 main（或走 GitHub REST API 更新文件）
        → CI（GitHub Actions）自动构建 release APK
        → apksigner 验签 → 发布到 GitHub Release（tag 带时间戳）
        → 从 Release 下载 app-release.apk 安装验证
```

- 构建结果以 Release 形式产出：https://github.com/d1667018881/cloudbox/releases/latest
- 编译是否通过 = 看 CI run 是否 success（Actions 页面）
- 想手动触发一次构建（不 push）：GitHub 仓库 Actions 页 → Build APK → Run workflow
- 唯一需要本地做的事：改代码文件（可用任意编辑器，或用 GitHub 网页在线编辑）
- 如果改动涉及新文件，直接编辑仓库对应路径即可，不要动 `app/keystore/` 下的签名文件

### 7.2 签名（可覆盖安装的关键）
- keystore：`app/keystore/cloudbox-release.keystore`
- keystore 密码与 keyAlias：`cloudbox`（密码已从文档移除，见下方说明）
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

## 9. 参考资料与逆向来源（源码出处明细）

> 接口实现的每一处都尽量有出处，便于后续核对。**若蓝奏云接口变更，优先对照以下来源中
> 更新最近的资料重新验证，不要凭本文档的记忆值猜测。**

### 9.1 主依据：zaxtyson/LanZouCloud-API（MIT 协议）
- **仓库**：https://github.com/zaxtyson/LanZouCloud-API（Python）
- **版本**：master 分支，commit `3bb917f`；2025 年仍活跃维护（PR#69 于 2025-10 修复
  `lanzouo.com` 域名判定，说明接口持续有效）
- **LICENSE**：MIT（宽松，可参考/借鉴实现）
- **本项目的借鉴点（逐项）**：
  | 借鉴点 | 出处 |
  |---|---|
  | task 编号速查表（§5 全部编号） | `lanzou/api/core.py` 逐字核对 |
  | 登录态列表 `task=5` 只需 folder_id/pg（推翻旧教程 t/k 方案） | `get_file_list` |
  | 子文件夹 `task=47` 带 `?uid=` | `get_dir_list` |
  | 上传 `fileup.php` 的 `folder_id_bb_n` 参数名（非 folder_id） | `_upload_small_file` |
  | 直链拼接 `dom + '/file/' + url`（非 dom+url 直拼） | `get_direct_url` |
  | 回收站 `mydisk.php` + formhash 流程（每次现取，不可复用） | `recycle` 系列方法 |
  | 分享链接获取 task=22/18（文件拼 is_newd+f_id） | `get_share_info` |
  | `acw_sc__v2` 反爬 cookie 计算算法 | `utils.py calc_acw_sc__v2`（社区逆向） |
  | 上传/批量操作延时防封思路 | `set_upload_delay` 同款 1-3s 随机延时 |

### 9.2 直链解析交叉验证：hanximeng/LanzouAPI（PHP）与 xhgzs/LanzouApi（PHP）
- https://github.com/hanximeng/LanzouAPI 、 https://github.com/xhgzs/LanzouApi
- 用途：验证 `ajaxm.php` 的 `action=downprocess&sign&file_id&p` 参数、
  sign 提取正则（`'sign':(.+?),` 与 `sign=(\w+?)&` 两种形态）、直链响应结构 `{zt, dom, url, inf}`
- 两处独立来源相互印证后才写入代码（信息交叉验证原则）

### 9.3 架构/交互思路参考（未抄代码）
| 项目 | 语言 | 参考点 |
|---|---|---|
| Yu2002s/SplitLanzou | Kotlin/Android | 安卓原生客户端架构（双面板、分卷上传、直链提取） |
| chenhb23/lanzouyun-disk | Electron | 批量操作、断点续传交互思路 |
| rachpt/lanzou-gui | Python/PyQt | 分卷上传、批量任务队列 |

> LICENSE 说明：以上仅借鉴架构与交互**思路**，未直接复制任何代码；
> 若后续需要直接参考实现，先确认目标仓库为 MIT/Apache 等宽松协议，GPL 项目只借鉴思路。

### 9.4 其他信息来源
- 域名现状调研：31du.cn 域名更换文章（2025）、tyut.tech 蓝奏云解析文章（2025，`lanzouh.com`）、
  binmt 论坛域名列表帖（2025-12）
- 上传限制：爱企查/php中文网多来源确认免费用户 100MB；分卷命名 `.zip/.z01/.z02`
  （`.001/.002` 被拦截，博客园实测）
- 需求规格中"会员 200M-210M 额度"等社区传闻**未采信**（无权威佐证，未写入任何业务逻辑）

---

## 10. AI 接手第一步（Checklist）

1. ✅ 完整阅读本文件 + `README.md`
2. ✅ 拉取代码（https://github.com/d1667018881/cloudbox，私有，需 token）
3. ✅ 对照 §9 的参考资料核对 §5 的 task 表是否仍然有效（蓝奏云接口会变）
4. ✅ 核实域名池（§7.4）是否仍可用（被墙/抢注域名及时清理）
5. ⚠️ 改动前先看 §6 的坑列表；**改完直接 push 到 main**，CI 自动构建验证（Actions 页面看 run 结果，Release 下载 APK）
6. ⚠️ 发布流程：push main 自动构建发 Release；**不要动 keystore**（动了就无法覆盖安装）

---

## 11. 代码审查修复记录（2026-08-26，外部复审 AI 全量审查）

外部审查（CODE_REVIEW.md，对照 commit 83bb3d5）共 32 条问题，**全部修复**并已通过 CI 编译验证
（release v0.1.0-202608261408 起）。逐条销账：

| # | 级别 | 问题 | 修复位置 |
|---|---|---|---|
| 1 | P0 | 登录后 Cookie 丢失（槽位绑定时序） | AuthRepositoryImpl.login：请求前预绑定槽位 + 失败回滚 |
| 2 | P0 | Cookie 持久化被属性串污染（大小写/截断） | CookiePersistenceJar.parseCookieLine 重构 |
| 3 | P0 | 上传 Worker 不分卷/无延时/失败误报成功 | UploadWorker 改调 uploadBatch + 失败检测 |
| 4 | P1 | acw_sc__v2 算法移植错误 | AcwScV2 按原版重写（unsbox + 单轮字节 XOR） |
| 5 | P1 | acw cookie 被 CookieJar 覆盖 | CookiePersistenceJar.putCookie + 接入解析流程 |
| 6 | P1 | CookiePersistenceJar 线程不安全 | 全部 cache 读写 synchronized(lock) |
| 7 | P1 | createFolder 前后对比顺序写反 | before 快照移到 create 前 |
| 8 | P1 | removeUid 误踢当前账号 | 仅当删除当前账号时才清 currentUid |
| 9 | P1 | 远程域名配置只生效一次 | 远程配置独立槽位 + 合并 DEFAULT<remote<userOverride + 启动拉取 |
| 10 | P1 | 远程域名无合法性校验（凭证窃取向量） | RemoteDomainSource 主字段 https+白名单校验，整份拒绝 |
| 11 | P2 | login 响应体未消费（连接泄漏） | body 统一消费/关闭 |
| 12 | P2 | 批量删除/移动/回收站缺防风控延时 | 循环加 1-3s 随机延时 |
| 13 | P2 | resolveFolder 静默丢弃失败项 | 返回 ResolveFolderResult（含失败计数） |
| 14 | P2 | Data.putStringArray 超 10KB 崩溃 | WorkContinuation 每批 50 文件串联 |
| 15 | P2 | 下载文件名未净化（路径穿越） | enqueue 内净化 / \\ .. |
| 16 | P2 | 剪贴板监听 Android 10+ 失效 | onResume 主动 checkNow |
| 17 | P2 | VIEW intent-filter 无 host 过滤 | Manifest 限定 lanzou 系 host + onCreate/onNewIntent 处理 |
| 18 | P2 | 缓存表主键错误 REPLACE 失效 | (accountUid,id) 复合主键 |
| 19 | P2 | 批量移动静默忽略文件夹 | UI 明确提示"文件夹不支持移动" |
| 20 | P2 | RetryInterceptor 对上传盲目重试 | fileup.php POST IOException 豁免重试 |
| 21 | P3 | getPage 忽略 HTTP 状态码 | 非 2xx 抛 ApiError.Server |
| 22 | P3 | isSynced 查错表 | 改查 searchIndexDao 行数 |
| 23 | P3 | allFiles 全量加载判空 | countForAccount COUNT 查询 |
| 24 | P3 | syncAll 失败静默截断 | 中断抛异常 → 返回 failure |
| 25 | P3 | LIKE 查询未转义 % _ | escapeLike + ESCAPE '\' |
| 26 | P3 | 过期 Cookie 不过滤 | loadForRequest 用 Cookie.matches 过滤 |
| 27 | P3 | Cookie 分桶键不一致 | 统一按 cookie.domain 分桶 |
| 28 | P3 | QrCodeUtil 逐像素 setPixel | IntArray 一次填充 |
| 29 | P3 | 分卷 volumeOrder .zip 排位错误 | .zip 改为最后一段（Int.MAX_VALUE） |
| 30 | P3 | 上传缓存文件覆盖/不清理 | 时间戳前缀 + Worker 完成后清理 |
| 31 | P3 | 版本号永不递增 | CI 注入 VERSION_CODE/VERSION_NAME |
| 32 | P3 | ksp 重复/FTS 文档误导/Referer 硬编码 | 去重 + 文档统一 + Referer 用当前配置域 |

遗留说明：
- 移动文件夹（#19 关联）：官方无接口，UI 明确提示，不实现模拟方案（LanZouCloud-API 的
  新建+移文件+删除有数据丢失风险）
- keystore 随仓库提交：私有仓库自用可接受；仓库转公开前必须移除并换签名（换签名 = 老用户无法覆盖安装）

---

## 12. V2 复审销账（2026-08-27，R1-R5 + N1/N2 全部修复）

V2 复审（对照 commit 56e2169）发现 7 项新问题，**全部属实并已修复**，最终 CI 转绿
（BUILD SUCCESSFUL in 6m8s，release v0.1.0-202608271333 起包含全部修复）。

| # | 级别 | 问题 | 修复 |
|---|---|---|---|
| R1 | 🔴 | 编译失败 22 连红：缺 combine/delay import、idx 未声明（**教训：上次误看中间 run 就宣称 CI 通过，必须验证最后一个 commit 的 run + BUILD SUCCESSFUL 日志**） | 补 2 个 import + var idx 声明 |
| R2 | 🔴 | delete() 双重 deleteDir 循环（编辑事故，文件夹删两遍+UI 报失败） | 删除重复循环，保留延时循环 |
| R3 | 🔴 | Room 主键变更未升 version → 老库升级必崩 | AppDatabase version 1→2 |
| R4 | 🟠 | 已发布 APK 只含前 10 项修复 | 合入后重新出包（新 Release 已含全部） |
| R5 | 🟡 | local.properties 误提交入库 | 已从远端删除 |
| N1 | 🟠 | 上传路径超 WorkManager Data 10KB 上限（V1 补录，分批修改未生效） | UploadViewModel chunked(50) + WorkContinuation 串联 |
| N2 | 🟡 | Worker 清理整个 uploads 目录（误删并行批次文件） | 只删本次 paths + 分卷临时目录 |

**教训记录**：file_edit/python 批量替换后必须 grep 验证实际内容（多次静默失败/编辑事故）；
CI 验证必须看**最后一个 commit 对应 run** 的结论 + 下载日志确认 "BUILD SUCCESSFUL"，
不能只看"最新 run"（中间 commit 的 run 成功不代表最终代码可编译）。

---

## 13. 登录迁移统一账号中心（2026-09-01，V4 根因分析 + 修复，复审 AI 亲自实现）

### 13.1 事故与排查过程（完整时间线，供未来接口失效时参照）

**症状**（2026-08-31 22:46 用户报告）：首次真实登录，账号密码正确，
报"登录失败（未获取到身份凭证）"。此前无人真正登录过——前四轮全是纯代码审查。

**排查步骤**（复审 AI，全部一手实测）：

1. **读现行代码**：App 登录 = Retrofit POST `login.php`（task=3&uid&pwd），
   域名拦截器把 `.php` 结尾的占位 host 请求路由到 diskMain（pc.woozooo.com）。
2. **拉 LanZouCloud-API 现行源码**（core.py）：其 `login()` 用的是
   `pc.woozooo.com/account.php` 取页面 → POST `mydisk.php`（task=3&uid&pwd&formhash，
   **手机 UA**）——跟 App 的 login.php 完全不是一回事。这说明 App 从第一版起
   就没按参考实现做过登录。
3. **实测旧端点（锤死）**：
   - POST `pc.woozooo.com/login.php` task=3 → 404 页（HTML 内含 `pan.lanzou.com/?404`），无 Set-Cookie——与用户报错完全吻合；
   - POST `up.woozooo.com/login.php` → HTTP 404；
   - GET `pc.woozooo.com/account.php`（桌面/手机 UA 双测）→ 830B JS 跳转壳：
     `document.location="https://accounts.woozooo.com/accounts.php?action=login&ref=pc.woozooo.com"`——**连 LanZouCloud-API 的旧流程也死了**。
4. **实测新端点（逆向出完整协议）**：
   - GET `accounts.woozooo.com/accounts.php?action=login&ref=pc.woozooo.com` →
     返回 acw_sc__v2 挑战页（`var arg1='…'` 混淆 JS）；
   - 用仓库内 AcwScV2 同款算法（unsbox+hex_xor，V2 轮已与原版逐行对齐）本地算出
     挑战值，带 cookie 重 GET → 真登录页（含 `var task ='uselogin'`、AJAX 提交逻辑、
     `window.location.href = date.msgs` 中转跳转）；
   - POST task=uselogin 假凭证 → JSON `{"zt":0,"msgs":"用户名不正确"}`（端点活、
     参数对、错误文案直出）；对照组 task=login → 返回新挑战页（证实 task 值敏感，
     必须用 uselogin）。
5. **根因结论**：登录功能从第一版起就打在已死亡的接口上。接口存活性只有实测能验——
   **这是纯代码审查（含历轮复审）的结构性盲区**。

### 13.2 现行登录协议（全部实测验证，除第 3 步中转链）

```
1. GET  https://accounts.woozooo.com/accounts.php?action=login&ref=pc.woozooo.com
   （桌面 UA；首次访问返回 acw 挑战页 var arg1='…' → 本地 AcwScV2 计算
     acw_sc__v2 cookie 写入 CookieJar → 重 GET 验证通过）
2. POST https://accounts.woozooo.com/accounts.php
   Header: X-Requested-With: XMLHttpRequest
   Form:   task=uselogin & username & password & ref=pc.woozooo.com
   → JSON {"zt":1,"msgs":"<中转鉴权URL>"} 或 {"zt":0,"msgs":"<中文错误>"}
   （若响应是挑战页 = 挑战 cookie 缺失/过期，解挑战后重试一次）
3. GET 中转 URL（OkHttp 自动跟随重定向链）→ 链上 Set-Cookie phpdisk_info
   ⚠️ 第 3 步的跳转链细节无法用假凭证实测，真机验证见 §13.4
4. 成功判定 = CookieJar.isLoggedIn()（phpdisk_info 到手，跨域桶查找）
```

### 13.3 代码改动清单（commit 见 git log "fix(login)"）

| 文件 | 改动 |
|---|---|
| `AppConstants.kt` | 新增 ACCOUNT_CENTER_BASE / _LOGIN_URL / _SUBMIT_URL / _REF_HOST 四常量（含下线证据注释） |
| `AuthRepositoryImpl.kt` | login() 重写为账号中心四步流程；新增 helpers：getWithAcwChallenge / postLogin / httpGet / httpPostLogin / solveAcwChallengeIfPresent；保留槽位预绑定+回滚（V2 #1）、catch 回滚；类 KDoc 全量更新为新协议 |
| `LanzouApiService.kt` | 删除死端点 login()（task=3），留注释指路 AuthRepositoryImpl |
| `AI_MAINTENANCE.md` | §4.2 更新登录判定描述；新增本节 §13 |

**设计决策记录**：
- 挑战 cookie 走 `putCookie` 而非手动 Cookie header——OkHttp BridgeInterceptor
  会在 jar 非空时整体替换手动头（V2 #5 同款教训）；
- accounts.woozooo.com 是真实域名，域名拦截器只重写占位 host 请求，天然放行，
  无需改拦截器（V4 报告 L2 预设的改动实际不需要）；
- 挑战解算重试一律**上限一次**（bounded），防挑战页死循环；
- 白名单无需改：accounts.woozooo.com 以 `.woozooo.com` 后缀命中
  TRUSTED_SHARE_HOSTS / isTrustedCookieDomain（V4 L5 预设确认，实测相符）；
- 登录失败文案优先用 JSON msgs 原文（"用户名不正确"/"密码错误"），
  extractLoginError() 关键词表保留为 HTML 兜底。

### 13.4 验收清单（真机）

- [ ] 正确账密登录 → 进文件列表，杀进程重启登录态保持
- [ ] 错误密码 → 提示"密码错误"（不再是"未获取到身份凭证"）
- [ ] 首次登录触发 acw 挑战自动通过（算法已在 2026-08-31 实测可过线上挑战）
- [ ] 中转跳转链 phpdisk_info 落库（第 3 步唯一未实测环节，若失败按报错
      "登录跳转未获取到凭证"反馈，回落 Cookie 导入）

### 13.5 流程教训（写给所有参与 AI）

1. **接口类功能必须实测销账**：涉及第三方服务端点的功能，修复后必须真机走通一次
   才能宣布"已修复"；代码审查再细也验不了服务端现状。
2. **注释里的"部分账号仍可用"是过时文档**——login.php 的 KDoc 误导了两轮 AI
   （开发 AI 照抄、复审 AI 未质疑）。对"接口可能已死"的怀疑要优先实测。
3. 参考项目（LanZouCloud-API）的"现行"实现也可能过期（account.php 同样死亡），
   迁移接口时参考项目只提供线索，结论必须实测。


---

## 14. V5 真机反馈三连修（2026-09-01，上传假成功/幽灵文件夹/上传入口 UX）

用户真机验收 v0.1.93（登录已通过）后报告三问题。定位与修复过程：

### 14.1 "上传显示成功实际没有上传"（P0）

**排查**（复审 AI）：
1. 读全链路（UploadScreen→ViewModel→Worker→Repository→拦截器），zt==1 判定与
   LanZouCloud-API 一致；无 Cookie 实测 fileup.php 返回 404 HTML（Gson 解析必抛
   异常→显示失败），排除"未授权却报成功"。
2. 定位真凶：**上传走独立 Tab + WorkManager 后台执行，FileListViewModel 完全不感知
   上传完成**——传完切回网盘页看到的是旧列表，用户以为没传（实际服务端已有文件，
   传到 targetFolderId，默认根目录）。
3. 顺带锤出一条真·假成功路径：Worker 里 `paths.map{File(it)}.filter{it.exists()}`
   若缓存文件全丢（系统清 cacheDir），results 为空 → failed 为空 → 报"上传完成"
   但零上传。

**修复**：
- 上传完成后发 `uploadFinished` 事件 → 网盘页收事件自动 refresh()（见 14.3 重构）
- Worker：files 全丢失时把全部路径计入失败名单（"本地缓存文件已丢失"），
  不再静默报成功

### 14.2 "二级目录里出现一级目录名（幽灵文件夹）"（P1）

两个独立根因，都修：
1. **loadPage 竞态**：快速导航 root→A→B 时，A 的迟到响应覆盖 B 的列表
   （无请求序号防护）。修复：`loadSeq` 序号，过期响应直接丢弃。
2. **task=47 的 info 字段被映射成文件夹**（"兼容两种形态"的臆测代码）：
   参考实现（LanZouCloud-API get_dir_list）只解析 text——info 是元信息字段，
   映射成文件夹会注入服务端不存在的幽灵条目。修复：删 info 映射，DTO 注释更正。
   （该缓存还污染了搜索索引 fileCacheDao——幽灵条目随 insertAll 入库）

### 14.3 上传入口并入网盘页（UX，用户指定方案）

用户："不改成网盘页点击+上传呢"。重构：
- MainScreen 底部导航 4 Tab → 3 Tab（网盘/解析/我的），上传 Tab 移除
- 网盘页 FAB 改弹出菜单：「新建文件夹 / 上传文件到当前目录」
  （SAF 多选 → enqueueUpload(uris, 当前目录id) → 底部进度横幅 → 完成自动刷新）
- UploadViewModel 重写：enqueueUpload 入口 + 全局进度（已完成批次累计 + 批内进度，
  顺带修了 V3 P3 的 total 批间跳变）+ uploadFinished 事件；SAF 拷贝移 IO 线程
- UploadScreen.kt 删除（git 历史可找回）；UploadWorker 协议不变

### 14.4 验收清单（真机 v0.1.94+）

- [ ] 网盘页 + → 上传文件到当前目录 → 选 2 个文件 → 进度横幅 → 完成后列表自动
      出现新文件（不用手动刷新）
- [ ] 在子目录里上传 → 文件落在该子目录（不再是"传到根目录找不到"）
- [ ] 快速连点进入二级目录 → 列表内容与面包屑一致，无一级目录内容残留
- [ ] 上传中切 Tab/杀进程 → WorkManager 后台继续传（回到网盘页进度恢复显示）

### 14.5 V5 自查复审（2026-09-02，用户指令"再次检查"）

自查范围：V5 三连修涉及的全部文件逐一重读。发现并修复三个残留问题：

| # | 级别 | 问题 | 修复 |
|---|---|---|---|
| S1 | 🔴 | **杀进程恢复缺口**：上传中杀进程 → 重进 App → ViewModel 重建丢失对在途 Worker 的观察。WorkManager 后台传完后 uploadFinished 无人发射 → **列表又不自动刷新**（V5 主修缺陷的残留路径）；§14.4 第 4 条"进度恢复显示"承诺落空 | Worker 请求加 TAG_UPLOAD_SESSION；ViewModel init 凭 tag 查询在途批次重新接管：已完成批（SUCCEEDED）的失败名单/文件数并入（进度正确基数续算）、恢复进度横幅、完成事件重接管 |
| S2 | 🟠 | **enqueue 防重入竞态**：uploading 检查在协程外，SAF 拷贝期间（大文件数秒）uploading 仍 false，再点上传 = 双会话并行、currentWorkIds 互相覆盖 | 拷贝前置位 uploading=true（失败回滚 false） |
| S3 | 🟠 | **Worker 部分缓存丢失静默**：v0.1.95 只修了"全部丢失"，部分丢失（选 3 传 2 丢 1）时丢失文件无声消失 | 丢失文件按路径精确比对（同名不误判）计入失败名单，文案"部分本地缓存文件已丢失" |
| S4 | 🟡 | Snackbar 重放：Tab 切换重建 composition，LaunchedEffect(message) 对未消费的同一 message 重放 | 消费即清（dismissMessage） |

v0.1.96 起生效。修复方 = 复审 AI 本人（V5 改动的自查，方法论：对自己写的代码也按外部审查标准走查一遍——竞态/进程死亡/资源清理/重入四个面挨个过）。

### 14.6 二轮自查（2026-09-03，用户"不想重复安装"，一次性清零）

按里程碑全读标准重审 V4+V5 全部改动（竞态/进程死亡/资源清理/重入四面向），新增修复：

| # | 级别 | 问题 | 修复 |
|---|---|---|---|
| S5 | 🔴 | **多会话 tag 混淆**：全部批次共享一个 tag。传两批文件 + 杀进程后，init 恢复会把已完成旧会话的文件数错算进新会话的进度基数（进度虚高/total 错乱） | 每次入队生成会话 uuid：批次带"会话 tag + 批大小 tag"，init 按会话分组、只接管含未完成批的会话 |
| S6 | 🟠 | **返回键被吞**：BackHandler 无条件注册，根目录按返回键无反应，无法退出 App | enabled = 多选或非根目录；根目录放行系统默认行为 |
| S7 | 🟡 | **离线上传立即报失败**：无网络约束，离线时入队即执行、直接把失败写进名单 | Worker 加 CONNECTED 约束，离线挂起等网自动续 |
| S8 | 🟡 | **enqueue 异常卡死**：WorkManager 入队抛异常时 uploading 永久 true，后续上传全被挡 | runCatching 包裹，失败复位 uploading + 提示 |
| S9 | 🟡 | **Tab 切换瞬间丢刷新事件**：SharedFlow 无 replay，上传恰好在离开网盘页时完成，事件丢失 | replay=1，新订阅者（重进网盘页）补投一次 |

编译教训补充（上轮两连红）：无本地 SDK 环境，WorkInfo 只暴露 outputData/progress/tags/state
（**没有 inputData**，那是 CoroutineWorker 的）；getWorkInfosByTag 返回 ListenableFuture 非 suspend。
不确定的 API 一律先查证或从已编译通过的既有代码里找同款用法。

v0.1.99 起生效。验收补充：传两批文件（第二批在第一批完成后）→ 杀进程 → 重进应只接管第二批的进度基数。
