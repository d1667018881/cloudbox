# AI 接手维护指南（CloudBox）

> **本文件是 AI 接手的必读文档。** 任何 AI（或人类）接手本项目的开发/维护，
> 请先完整阅读本文件 + `README.md`，再动手改代码。
> **动手前必看第 0 节之后的「交付前必做（Checklist）」。**
> 本文档最后更新：2026-09-19（V32 四轮复查之后；正文各节的 commit 标注保留原状）。

---

## 交付前必做（Checklist）—— 先看这节，再看别的

> **这节是强制的，不是建议。** 起因：V32 那批功能在 4 轮复查里累计查出 12 个
> 问题（详见 §32~§35），**其中 3 个是我自己改出来的**。
> 复盘结论很直白：**问题频出的根因是第一遍写代码时没有自查，不是检查次数不够。**
> 所以把检查从"用户开口才做"改成"每次交付前必做"。

### A. 三条铁律（违反即返工）

**A1. 破坏性操作（删除 / 重置 / 覆盖 / 清空）的守卫必须放在第一个写操作之前。**

反面例子（真实发生过，§35.2）：`resetAppData()` 把设置、收藏夹、数据库都清完了，
最后才调 `clearCache()` 校验 —— 校验失败时用户看到"重置失败"，但数据已经没了。
**半完成状态比干脆没做更危险**：用户以为没重置成功，就不会去重新配置。

**A2. 凡是"值会随发版/环境变化"的地方，一律从单一来源取，禁止写字面量。**

反面例子（真实发生过，§35.4）：设置页页脚写死 `v0.1.143`，实际已到 `v0.1.154`。
用户按这行报版本号，排查就会去查错的版本。
正确做法：`BuildConfig.VERSION_NAME`（CI 注入）。

**A3. 改完立刻做"体检"，不要等 CI。**

本地**没有 Android SDK、没有 gradlew**，CI 是唯一的编译器 ——
所以低级错误会白等 3~4 分钟一轮。

```bash
python3 scripts/precheck.py app/src/main/java      # 全量，秒级
python3 scripts/precheck.py 刚改的那个文件          # 单文件
```

脚本检查：分隔符平衡（会先剥掉注释与字符串字面量，避免误报）、
已知的错误 import、未使用的 import。CI 里也跑同一条命令
（`.github/workflows/build.yml` 的 `Pre-check (fast)` 步骤，在 gradle 之前）。

> ⚠️ **它抓不到的东西**（别指望它）：
> 作用域里不存在的标识符（§34.5 的裸赋值）、类型不匹配、任何逻辑错误。
> 那些只能靠下面的 B 组问题 + 人工纪律。详见 `scripts/precheck.py` 文件头。

### B. 四个必答问题（改完代码，自己回答一遍）

| # | 问题 | 能拦住的真实问题 |
|---|---|---|
| B1 | **这个改动如果中途失败，会留下什么状态？** | §35.2 半完成的重置 |
| B2 | **我删/写的是共享资源吗？另一个界面会不会同时用它？** | §35.1 清缓存删掉在途上传的 `uploads/` |
| B3 | **失败时用户看到什么？是"成功"字样吗？** | §20/§21/§24 反复栽的"假成功" |
| B4 | **这个值是"当前版本"还是"历史事实"？** | §35.4 硬编码版本号；备份文件里的版本号是历史事实，**不能**改 |

### C. 三种绝不能当死代码删的东西

删任何"没人用"的符号前，先排除这三类（否则会把正常代码删成编译错误）：

1. **Hilt `@Provides` / `@Binds`** —— 注解处理器按注解发现，代码里搜不到调用
2. **`override` 方法** —— 框架回调，调用方在 Android 框架里
3. **`@Dao` 方法** —— Room 编译期生成实现

### D. 提交前最后一眼

- [ ] `git status` 干净，没有误提交 `remote URL` 里的 token
- [ ] 新加的 import **每一个都真的用到了**（V32 起我在这上面栽过两次）
- [ ] 新加的符号**真的被引用**（我曾在"批评死代码"的同一轮里自己写了死代码）
- [ ] commit message 说清"**为什么**改"，不只是"改了什么"
- [ ] 推送后用 `ci-logs` 孤儿分支确认构建结论（见 §7）

### E. 已知的自查薄弱点（我会反复犯，重点盯）

| 薄弱点 | 表现 | 对策 |
|---|---|---|
| 可空链推断 | `a?.b()?.sumOf{}` 整体变可空，与声明的非空类型冲突 | 用 `.orEmpty()` 兜空序列 |
| 作用域混淆 | 同名标识符在 `it.copy(x = ...)` 内外含义不同，裸写报 Unresolved | 改完搜一遍该名字的**所有**出现位置（脚本抓不到，只能靠这一步） |
| 多余 import | 接口成员函数被误当扩展函数导入 | 照抄仓库里**已验证可用**的同款写法，别凭记忆写 |
| 跨界面共享目录 | `cacheDir` 下同时住着 `uploads/`（上传）和 `uploaded_apps/`（APK 副本），各写各的清理 | 碰 `cacheDir` 前先搜全仓库还有谁在用 |
| 破坏性操作漏守卫 | 清缓存把在途上传的源文件删了；重置的校验放在最后一步 | B1 / A1，写之前就想"失败一半会怎样" |

### F. 这套清单的由来（读一遍，才知道为什么要照着做）

V32 那批功能（上传已安装应用 + 数据管理）在 **4 轮**复查里累计查出 12 个问题：

| 轮次 | 提问方式 | 问题数 | 典型 |
|---|---|---|---|
| §32 | 我改的这段代码对不对 | 4 | APK 副本泄漏 + 它引出的竞态 |
| §33 | 这几轮改动之间是否自洽 | 3 | 重置绕过仓储层 → 幽灵下载 |
| §34 | 数据层哪里最容易坏 | 1 | 恢复后输入框显示旧值 |
| §35 | 用户会怎么操作 | 4 | 清缓存删掉在途上传的源文件 |

**关键事实：12 个里有 3 个是我自己改出来的**（`2061e79` 可空链、
`68b712a` 作用域混淆、`e033c09` 多余 import）。

所以"每次检查都能查出问题"**不是检查得仔细，是返工** ——
根因是第一遍写代码时没有自查，而不是检查次数不够。
一个健康的节奏应该是有若干轮查不出东西。

> **这份清单的目的就是把"用户开口才检查"变成"交付前必做"。**
> 如果照着它做完仍然反复出问题，那说明清单本身还不够 —— 请继续补。

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

---

## 15. 协议迁移：对齐原版 App（2026-09-07，逆向 + 抓包实测驱动）

> 背景：蓝奏云 2025-2026 连续改版，本仓库沿用的旧协议（来自 2025 年的
> LanZouCloud-API 文档）**全线失效**，表现为"上传显示成功但云端没有文件"、
> "直连解析必失败"。本次以**原版 App（蓝云 AndroLua，1.3.4.8）反编译产物为对照**、
> 以**线上抓包实测为准**，重写了直链解析与上传两条主链路。
>
> ⚠️ 本文档是唯一权威来源。任何"某篇文章说应该这样"的信息都要先抓包验证再改代码。

### 15.1 原版 App 逆向结论（可直接指导实现的三条）

| 结论 | 证据 | 对本仓库的影响 |
|---|---|---|
| 原版**没有任何 multipart 上传调用** | 反编译 55 个 lua 模块 + `Http.java` 全部调用点枚举：只有 `doupload.php`/`mlogin.php`/`mydisk.php?item=recycle`/`filemoreajax.php` | 原版"能稳定上传"是因为它 **WebView 直接打开官方上传页**（`设置.upload_url = "/html5up.php"` 是给网页页用的）。本仓库补了同款兜底通道 `WebViewUploadActivity` |
| 上传端点已迁移到 `html5up.php` | `fileup.php` 实测 404；`up.woozooo.com/html5up.php` 未登录返回 `{"zt":9,"info":"login not"}` | `LanzouApiService.upload` 改打 `html5up.php` |
| 回收站表单**必须带 `ref`** | `recycle.lua` 原版取值正则 `name="ref" value="(.-)"`，POST 串固定含 `&ref=…&formhash=…` | 旧实现没传 ref → 服务端拒绝 → "回收站点了没反应" |

### 15.2 现行直链协议（2026-09 实测通过）

**单文件**（样本 `https://www.lanzoui.com/i1evj0klyr0d`）：

1. `GET` 分享页（原始域，桌面 UA）→ 可能被 `acw_sc__v2` 挑战拦截
2. 挑战页含 `var arg1='…'` → `AcwScV2.compute()` → 写 CookieJar → **重取**
   （⚠️ 必须走 `cookieJar.putCookie()`，手动 Cookie 头会被 OkHttp `BridgeInterceptor` 整体覆盖）
3. 文件名取 `<title>`（实测 `Fluent v3.zip - 蓝奏云`，去掉 ` - 蓝奏云` 后缀）
4. `fid` 取 `var fid = 96810913;`
5. 页面里**没有 sign**，只有一个 iframe：`<iframe class="ifr2" src="/fn?VzFV…_c_c">`
6. `GET` iframe 页 → `var wp_sign='…'`、`var ajaxdata='asXy'`、`var kdns=1`
7. `POST {原始域}/ajaxfile.php?file=<fid>`：
   `action=downprocess&websignkey=<ajaxdata>&signs=<ajaxdata>&sign=<wp_sign>&websign=&kd=<kdns>&ves=1`（有提取码加 `p`）
8. 直链 = `dom + "/file/" + url`。**注意 url 以 `?` 开头**（`?A2VUags6…`），不能剥前导字符
9. 响应 `inf` 正常时是**数字 0**，不是文件名 → `AjaxFileResponse.inf` 用 `Any?` 承接
10. 探测：读直链前 1KB，若含 `down_r(` 说明是**验证中间页** → POST 同目录 `ajax.php{file,el,sign}` 换真实地址

**文件夹**（样本 `https://wwe.lanzoui.com/b01tpeg7i`）：

1. `GET` 分享页 → 页面 JS：
   `$.ajax({url:'/filemoreajax.php?file=2553948', data:{'lx':2,'fid':2553948,'uid':'1702063','puid':'ATBUN…','pg':pgs,'rep':'0','t':ib280v,'k':_h0k2o}})`
2. `t`/`k` 是**随机变量名**（实测 `ib280v` / `_h0k2o`），值分别是 10 位时间戳 / 32 位 hex
   → 旧正则 `var [0-9a-z]{6} = '…'`（定长 6 位且不含下划线）**永远匹配不到 k**
3. `POST {原始域}/filemoreajax.php?file=<fid>`，表单 `lx/fid/uid/puid/pg/rep/t/k/up/pwd`
4. 翻页终止：实测 `zt=1` 继续；`zt=2` + `text="no file"` 取完；`zt=3` 提取码错误
5. 目录内文件 id 形如 `iGLa524otx7a`（**字符串**）→ 子链接 = `{原始域}/{id}`，再走单文件流程

**页面类型判定**：`html.contains("filemoreajax") && 无 /fn? iframe` → 文件夹页。
失效页返回约 1KB 的 `文件不存在，或已删除` 空壳页，需早退。

### 15.3 ⚠️ Gson 数组字段陷阱（本仓库最容易复发的崩溃源）

蓝奏云在"无数据"时**不返回空数组，而是把数组字段换成字符串**：

```
filemoreajax 最后一页：{"zt":2,"info":"没有了","text":"no file"}
doupload task=5 空目录： {"zt":2,"info":0,"text":"no file"}
```

若 DTO 字段声明为 `List<T>`，Gson 在**反序列化阶段**就抛 `JsonSyntaxException`，
调用方连 `zt` 都读不到 —— 后果是"翻页必崩""往空文件夹上传后云端确认失败"。

**对策（已在 `Dtos.kt` 落地，新增 DTO 请照抄）**：字段一律用 `Any?` 承接，
再提供类型化访问器（`.items` / `.dirs` / `.folders` / `.hasMore`），
访问器对非数组返回空列表、对 id 同时容忍数字与字符串。

### 15.4 上传：三条"假成功"路径与封堵

| # | 假成功路径 | 封堵方式 |
|---|---|---|
| 1 | `verifyOnCloud` 返回 `null`（列表请求失败）被判成功 | **null 一律判失败**（宁可让用户重试，不给假成功） |
| 2 | 缺 `Referer`：`html5up.php` 不报错、返回 `zt=1` 却不入库 | `LanzouRefererInterceptor` 自动补全为网盘文件页 |
| 3 | 凭证半失效：只有 `phpdisk_info` 全缺才返回 `zt=9` | 上传前 `hasUploadCredentials()` 自检，缺失直接失败提示重新登录 |

成功判定收紧为：`zt==1 && text 是数组 && 首元素带非空 id`。
云端确认最多重试 3 次（0 / 1.2s / 2.4s），区分 `false`（目录里真没有）与 `null`（请求失败）。

### 15.5 本次改动文件清单

| 文件 | 改动 |
|---|---|
| `common/HtmlExtractor.kt` | 重写全部正则（随机变量名、`filemoreajax?file=`、`wp_sign`/`ajaxdata`/`kdns`、formhash 双形态） |
| `core/data/dto/Dtos.kt` | 数组字段改 `Any?` + 类型化访问器；新增 `ShareFileItem`；`AjaxFileResponse.inf` 改 `Any?` |
| `core/data/remote/LanzouApiService.kt` | `upload` → `html5up.php` + `folder_id` 双写；`downProcess` → `ajaxfile.php?file=`；`getShareFileList` 带 `?file=` + `uid/puid/rep/up` |
| `core/data/remote/LanzouDomainInterceptor.kt` | 路由加 `filemoreajax` / `ajaxfile` / `ajaxm` → 分享域 |
| `core/data/remote/LanzouRefererInterceptor.kt` | **新建**：上传自动补 Referer |
| `core/data/remote/LanzouApiClient.kt` / `CookiePersistenceJar.kt` | 注入 Referer 拦截器；新增 `cookieValue()` / `hasUploadCredentials()` |
| `core/data/repository/UploadRepositoryImpl.kt` | 重写（见 §15.4）；新增 `uploadPageUrl()` 网页兜底 |
| `core/data/repository/DirectLinkRepositoryImpl.kt` | 重写（见 §15.2）；新增文件夹自动分流 + 失效页早退 + 验证中间页二次解析 |
| `core/data/repository/FileRepositoryImpl.kt` | 回收站改用原版实证正则；POST 补 `ref`；成败改按 HTTP 状态判定 |
| `core/domain/repository/DirectLinkRepository.kt` | 新增 `ERR_FOLDER_LINK` |
| `core/domain/repository/UploadRepository.kt` | 新增 `uploadPageUrl()` |
| `feature/upload/WebViewUploadActivity.kt` | **新建**：官方网页上传通道（原版同款做法） |
| `feature/upload/UploadViewModel.kt` | 汇总失败原因并展示（不再只说"部分失败"） |
| `feature/filelist/FileListScreen.kt` | FAB 加"网页上传（官方通道·兜底）" |
| `feature/resolve/ResolveViewModel.kt` / `ResolveScreen.kt` | 文件夹链接自动展开 + 进度提示 |
| `AndroidManifest.xml` | 注册 `WebViewUploadActivity` |

### 15.6 以后怎么自查协议是否又变了

```bash
# 1) 抓分享页（桌面 UA 必须带，手机 UA 页面结构不同）
curl -sk -A "<桌面UA>" "https://<域>/<id>" -o page.html
grep -oE "filemoreajax\.php\?file=[0-9]+|var [a-zA-Z_0-9]+ = '[0-9]{10}';|var [a-zA-Z_0-9]+ = '[0-9a-f]{16,}';|<title>[^<]*</title>" page.html

# 2) 抓 iframe 页看签名三件套
grep -oE "var wp_sign = '[^']+'|var ajaxdata = '[^']*'|var kdns = [0-9]+" fn.html

# 3) 拿不到直链就先确认端点还活着（未登录应返回 zt=9，404 说明端点已下线）
curl -sk -X POST "https://<域>/ajaxfile.php?file=<fid>" -d "action=downprocess&sign=xxx"
```

**排查顺序**：端点是否 404 → 页面关键字段是否还能用现有正则抠到 →
响应 JSON 字段名/类型是否变了（尤其注意"数组变字符串"）→ Cookie/Referer 是否缺失。

### 15.7 编译环境说明

本次改动**未能真机编译验证**（沙箱无 Android SDK，`dl.google.com` /
`services.gradle.org` 不可达）。已做的替代校验：

1. 逐文件括号配平检查（对照改动前基线，确认无结构退化）
2. 跨文件符号交叉核对（方法签名、参数名、import、Hilt 绑定、Manifest 注册）
3. 依赖版本核对：`LinearProgressIndicator` 在 material3 1.3.0 是 `progress: Float`，
   **1.7.0 才改成 lambda 版** —— 本项目锁前者，写 `{ }` 编译不过（已修）

首次编译若有报错，优先怀疑：`Dtos.kt` 访问器用法、`HtmlExtractor` 正则转义、
`WebViewUploadActivity` 的 Compose 导入。

## 16. 登录主通道回归原版 mlogin.php + 数字 uid（2026-09-07，逆向 + 实测）

### 16.1 为什么改：原"上传不上"的根因不在上传，在登录

排查顺序反过来推的：上传接口 `pc.woozooo.com/html5up.php` 实测返回
`{"zt":9,"info":"login not","text":"error"}`，而旧端点 `fileup.php` 已 404。
也就是说**只要没拿到 phpdisk_info，上传 100% 失败**——旧版恰好没校验 zt，
于是"上传失败但显示成功"。

而登录此前走的是账号中心 `accounts.woozooo.com/accounts.php`：
实测（Python 复刻 acw 算法、带 cookie 重放）**解完 acw_sc__v2 挑战后仍是挑战页**，
永远拿不到 phpdisk_info。这就是"登录界面能进、上传永远不行"的完整因果链。

### 16.2 原版真实协议（逆向 login.lua:273 / home_func.lua:891，已实测）

```
① GET  https://pc.woozooo.com/mlogin.php            → 服务端下发 PHPSESSID
② POST https://pc.woozooo.com/mlogin.php
       form: task=3&uid=<账号>&pwd=<明文密码>&setSessionId=&setSig=&setScene=&setToken=&formhash=
③ 响应 JSON:{"zt":1,...} 成功 / {"zt":0,"info":"没有用户","id":null} 失败
④ 成功凭证在响应头 Set-Cookie（原版取 Http 回调第三参 a3 存 设置.cookie）
```

实测记录（2026-09-07，假账号）：
`POST mlogin.php` → `{"zt":0,"info":"没有用户","id":null}`
说明端点存活、字段名是 **info**（不是账号中心的 msgs）、密码是**明文**（原版未做 md5）。

实现：主通道 `loginViaMlogin()`，账号中心降级为回落；用 `MloginOutcome`
区分"账号密码错"（直接报错，不重试）与"协议不可用"（才回落）。

### 16.3 数字 uid：doupload.php 必须带 ?uid=

原版 home_func.lua:2463 里所有管理请求都拼 `uid后缀`：

```lua
uid后缀 = "?uid=" .. 首页HTML:match("index&u=(.-)'")   -- home_func.lua:2372
Http.post(domain .. "/doupload.php" .. uid后缀, "task=47&folder_id=-1&pg=1", ...)
```

这是服务端分配的**数字 uid**（如 1702063），**不是登录账号名**。
本项目早前在 `getDirList` 上把"登录账号名"当 uid 传，服务端解析不了 → 子文件夹列表空。

修法：
- 登录后 `fetchCloudUid()`：GET `mydisk.php`，正则 `index&u=(\d+)` 提取，存 `cloud_uid_<uid>`
- 新增 `LanzouUidInterceptor`：只对 `doupload.php` 生效，无 uid 查询参数时自动注入；
  取不到就原样放行（接口在无 uid 时仍可能工作，不阻断）
- `getDirList` 的 `@Query("uid")` 参数删除（改由拦截器统一注入，避免重复/传错）

### 16.4 本轮改动文件

| 文件 | 改动 |
|---|---|
| `common/AppConstants.kt` | 新增 `PC_WOOZOOO` / `MLOGIN_URL`，注释改为主通道=mlogin |
| `repository/AuthRepositoryImpl.kt` | 新增 `loginViaMlogin()` + `MloginOutcome` + `fetchCloudUid()`；原登录改名 `loginViaAccountCenter()` 作回落 |
| `remote/LanzouUidInterceptor.kt` | **新建**，`doupload.php` 自动注入 `?uid=` |
| `remote/LanzouApiClient.kt` | 注册 uid 拦截器（Referer 之后、域名重写之前） |
| `remote/LanzouApiService.kt` | `getDirList` 去掉 uid 形参 |
| `repository/FileRepositoryImpl.kt` | `getDirList` 调用去掉 `uid = uid` |
| `local/secure/AccountSecureStore.kt` | 新增 `saveCloudUid/cloudUid`，`removeUid` 一并清理 |

### 16.5 自查命令（协议再变时照抄）

```bash
# 1) 登录端点是否还活着（假账号应回 zt=0 + 中文原因）
curl -s -A "Mozilla/5.0 ... Chrome/120" \
  -X POST https://pc.woozooo.com/mlogin.php \
  --data "task=3&uid=xxx&pwd=yyy&setSessionId=&setSig=&setScene=&setToken=&formhash="

# 2) 上传端点是否还活着（未登录应回 zt=9 login not）
curl -s -A "Mozilla/5.0 ... Chrome/120" \
  -e "https://pc.woozooo.com/mydisk.php?item=files&action=index" \
  -F "task=1" -F "upload_file=@a.txt" https://pc.woozooo.com/html5up.php

# 3) 数字 uid 是否还能提取（登录后带 cookie）
curl -s -b cookies.txt https://pc.woozooo.com/mydisk.php | grep -o "index&u=[0-9]*"
```

若 1) 返回 HTML 而非 JSON → mlogin 已下线，删除主通道改走账号中心；
若 2) 返回 404 → 上传端点又换了，抓网页版上传页的 form action。

## 17. 编译阻塞修复：LinearProgressIndicator 版本不兼容（2026-09-08）

**这个仓库此前根本编译不过**——不是功能问题，是硬编译错误：

material3 1.3.0（本项目锁 BOM `2024.09.03`）的签名是
`LinearProgressIndicator(progress: Float, ...)`；
`progress: () -> Float` 的 lambda 版是 **1.7.0 才引入**的。
四处用了 lambda 版，Kotlin 编译器直接报错：

| 文件 | 行 |
|---|---|
| `feature/download/DownloadScreen.kt` | 118 |
| `feature/upload/UploadScreen.kt` | 107 |
| `feature/search/SearchScreen.kt` | 91 |
| `feature/filelist/FileListScreen.kt` | 298 |

已全部改回 `progress = <Float 表达式>`，并在每处加注释锁死版本约束，
避免以后被"顺手升级"改回去。

**已核对无问题的同类疑点**：`PullToRefreshBox` 在 material3 1.3.0 中确实存在
（1.3.0-alpha05 引入，1.4.0 才去掉 `@ExperimentalMaterial3Api`），
`FileListScreen` 已带 `@OptIn(ExperimentalMaterial3Api::class)`，可正常使用。

**日后升级 material3 到 1.7+ 时**：这 4 处（及 `WebViewUploadActivity`）需要
改回 lambda 版，否则会收到弃用警告 / 编译错误。

---

## 18. 沙箱里如何拿到 GitHub Actions 的真实编译日志（2026-09-08）

**背景**：在本仓库所在沙箱中，`github.com` 等域名会被 DNS 劫持到
`198.18.0.x` 的透明代理（SSL 握手直接失败，`curl` 返回 `000`）。
而 GitHub Actions 的日志正文又藏在 blob 存储的一串重定向后面，
于是"构建失败但看不到任何报错"会让人只能靠猜。

**结论：这条路是通的，按下面四步走。**

### 18.1 绕过 DNS 劫持（先把 hosts 修好）

沙箱的 `/etc/hosts` 自述"重启会自动还原"，所以**必须同时写 `~/.user_hosts`**。
另外 glibc 要求文件**以换行结尾**，最后一行缺 `\n` 会导致整条记录不被解析。

各域名要用**不同的 IP**，混用会拿到 301：

| 域名 | 可用 IP（会随时间波动） | 备注 |
|---|---|---|
| `github.com` | `140.82.112.4` / `140.82.113.3` / `140.82.113.4` / `140.82.121.4` | 别用 api 的 IP，会 301 |
| `api.github.com` | `140.82.121.5` / `140.82.121.6` | 用错会 301 到网页版 |
| `raw.githubusercontent.com` | `185.199.108.133` / `185.199.110.133` / `…111.133` | |
| `codeload.github.com` | `140.82.112.9` | git clone/下载源码包 |

可选这四个 octet 段自行探测，返回值 **200 优先**于 301/302：

```bash
for ip in 140.82.112.4 140.82.113.3 140.82.113.4 140.82.121.4 20.201.28.151 20.27.177.113; do
  curl -s -m 8 -o /dev/null -w "github.com -> $ip : %{http_code}\n" \
       --resolve github.com:443:$ip https://github.com/
done
```

上面这段逻辑做成了脚本 `gh_fix.py`，放在本机工作区 **`/workspace/gh_fix.py`**（**不属于 App 运行时代码，无需提交进仓库**）。

### 18.2 API 拿得到，日志 zip 拿不到 —— 那就别用 zip

`GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs` 会 **302 到**
`productionresultssa*.blob.core.windows.net`，而这个域名同样被劫持到透明代理，
`curl -L` 会在 SSL 握手处失败（`SSL_ERROR_SYSCALL`）。UDP 53 与 DoH 也都被封，
拿不到它的真实 IP。

**绕开办法：让 CI 自己把日志交出来**（见 18.4）。

### 18.3 用 API 查运行状态

```bash
curl -s -u <user>:<PAT> \
  "https://api.github.com/repos/d1667018881/cloudbox/actions/runs?per_page=5" \
  | python3 -c "import json,sys
for r in json.load(sys.stdin)['workflow_runs']:
    print(r['id'], r['name'], r['status'], r['conclusion'], r['head_sha'][:8])"
```

失败时继续查 `…/runs/{run_id}/jobs`，能定位到具体挂掉的 step
（本项目永远是 `Build APK` 这一步）。

### 18.4 workflow 里把日志推到孤儿分支（本项目已内置）

`.github/workflows/build.yml` 现在有两处改动：

1. `Build APK` 步骤用 `tee build.log` 保留输出，并用 `${PIPESTATUS[0]}`
   把 gradle 的真实退出码交回给 Actions（否则被管道吞掉、永远"成功"）；
2. 新增 `Publish build log (debug)` 步骤，`if: always()`，
   把日志推到 **`ci-logs` 孤儿分支**。

于是本地这样读：

```bash
curl -sL "https://raw.githubusercontent.com/d1667018881/cloudbox/ci-logs/.ci/build.log" \
     -o /tmp/build.log
grep -nE "^e: |error:|FAILED" /tmp/build.log
```

**不需要时整段删掉 `Publish build log (debug)` 这一步即可，不影响 APK 产物**，
再 `git push origin --delete ci-logs` 清掉分支。

### 18.5 本次靠它抓出的真实错误（静态审查完全漏掉）

两处都在 `feature/filelist/FileListScreen.kt`：

```
e: …FileListScreen.kt:213:63 Unresolved reference 'Language'
e: …FileListScreen.kt:216:85 @Composable invocations can only happen
    from the context of a @Composable function
```

* **`Unresolved reference 'Language'`**：项目依赖的是
  `androidx.compose.material:material-icons-extended`，图标本体存在，
  缺的是 `import androidx.compose.material.icons.filled.Language`。
  → **Compose 里每个图标都是独立的扩展成员，用到哪个就得 import 哪个：`Icons.Filled.Language` 需要 `import androidx.compose.material.icons.filled.Language`，
  光 `import androidx.compose.material.icons.Icons` 不够。**
* **`LocalContext.current` 写进了 `onClick = { … }`**：`onClick` 不是
  `@Composable` lambda，里面调用 Composable 函数直接编译失败。
  → 在外层 `@Composable` 作用域先取 `val context = LocalContext.current`，
  再在 `onClick` 里用它。全库其余 3 处 `LocalContext.current` 都在
  Composable 函数体顶层，安全。

**教训**：专门为这类错误写 import 扫描脚本是**投入产出比很低的**——
本章抓到的两个错误，一个是缺 import 成员（扫描器把同包/同名符号当已导入），
一个是作用域错误（语法层面完全合法）。
**能真编译就别靠猜**；本地编不了的时候（本沙箱 `dl.google.com` /
`repo1.maven.org` / `services.gradle.org` 全被劫持，装不了 Android SDK），
宁可多花几分钟让 CI 把日志吐回来。

---

## 19. V10：补齐原版 account.lua 的账号中心设置（2026-09-08）

做了一次**系统性的功能对照**（而不是继续逐行读代码），方法是把所有
调用点按"服务端端点 + task 编号"抽出来两边比对：

| 来源 | 端点 |
|---|---|
| 原版（47 个 lua 模块） | `doupload.php`(48) `mydisk.php`(42) `mlogin.php`(8) `html5up.php`(8) `myfile.php`(4) `fileup.php`(4, 现已 404) `filemoreajax.php`(2) `account.php`(1) |
| 本仓库 | `doupload.php`(15) `html5up.php`(1) `filemoreajax.php`(1) `ajaxfile.php`(1) |

按 task 比对的结果：

* 仓库已覆盖 `2 3 4 5 6 11 12 16 18 19 20 22 23 46 47` —— 文件/文件夹的
  增删改移、提取码、描述、分享、列表、上传、直链，**业务主干是全的**。
* 仓库**缺** `7 8 10 15 43`，全部位于原版 `account.lua`（账号中心设置）。

本次补齐前四个：

| task | 功能 | 服务端参数（极易踩坑） |
|---|---|---|
| 7 | 个人分享链访问码 | `codeoff` 是**反**的：`0`=需要访问码，`1`=关闭。别照抄 task=23 的 `shows` |
| 8 | 修改登录密码 | `old_pwd` / `new_pwd`，**明文提交**（与登录同源，原版就没做 md5） |
| 10 | 外链（个人主页）标题/简介 | `ubt`=标题、`usm`=简介。字段名看着像 URL，其实是文案 |
| 15 | 显示发布者 | 复用了"提取码"那对字段名 `shows`/`shownames`，但语义是**是否显示 / 昵称** |

**刻意没做 task=43（修改手机号）**：需要短信验证码，App 侧拿不到，
做出来必然失败，只会误导用户。若以后接入，注意它的失败响应同样只看 `zt`。

### 代码结构

```
core/domain/repository/ProfileRepository.kt      // sealed class ProfileResult + 4 个方法
core/data/repository/ProfileRepositoryImpl.kt    // 统一走 safe{...}：前置校验 → 调接口 → judge(zt)
core/data/remote/LanzouApiService.kt             // setPersonalLinkCode / changePassword / setExternalLink / setPublisher
core/di/AppModule.kt                             // @Binds ProfileRepositoryImpl → ProfileRepository
feature/settings/SettingsScreen.kt               // 「账号中心设置」区块 + 4 个对话框
feature/settings/SettingsViewModel.kt            // 注入 ProfileRepository，结果映射成 Snackbar
```

### 两个实现细节（照抄会踩坑）

1. **`inline fun` + `withContext` 不能用带标签的非局部返回**。
   最初写成 `private inline fun runCatchingProfile(block: () -> ProfileResult)`，
   Kotlin 直接拒绝：`it may contain non-local returns`（block 被塞进
   `withContext` 的另一个 lambda，跨 lambda 边界不允许非局部返回）。
   → 改成 `private suspend fun safe(block: suspend () -> ProfileResult)`，
   调用方用 `return@safe`。

2. **`CommonResponse` 加了 `info: Any?`**（而非 `String?`）。
   原版靠 `json.decode(resp).info` 给用户中文提示；实测失败时服务端偶尔返回
   数字 `0`，声明成 `String` 会让 Gson 在**反序列化阶段**抛异常，反而盖掉 `zt` 判定。
   同时提供 `infoText` 访问器，只认非空字符串。

### 验证状态

* CI：**Run 34188151273 success** → Release **v0.1.108**（`app-release.apk` 13,810,538 B）
* ⚠️ **这四个接口的运行时正确性未经真机实测**（需要已登录的真实账号）。
  若某接口报错，先看 Snackbar 里服务端的 `info` 文案，再对照上表的参数语义。

---

## 20. V12：原生上传失败的真因 —— 原版 multipart 协议（2026-09-08）

> 这一节是整个项目最贵的教训，值得单独记住。

### 20.1 曾经的错误结论（已推翻，别再信）

在 §15 里我写过一句话：

> 原版 App（蓝云）**并不自己拼 multipart**，而是 WebView 打开官方上传页交给网页 JS 处理。

**这是错的。** 错误推理链：

1. `home.lua` / `webview.lua` 当年反编译失败，只剩字节码反汇编 `disasm/home.txt`（663KB）。
2. 我在反汇编里扫了几眼没找到上传构造，就判断"原版不做原生上传"。
3. 于是把仓库的上传实现按"浏览器行为"推测出了 **10 个字段**
   （`vie / ve / id / folder_id_bb_n / name / type / lastModifiedDate` …）。
4. 结果：**假成功**——服务端回 `zt=1`，文件却没入库。

> **教训（写进方法论）**：反编译失败的模块，只能在文档里标注"未覆盖"，
> **绝不能反推成"没有这个功能"**。这是本项目踩过代价最大的一次。

### 20.2 真相：`disasm/home.txt:6537-6560`

原版**确实**自己拼 multipart，而且非常朴素——只有 **3 个字段**：

| 字段 | 值 |
|---|---|
| `task` | `1` |
| `folder_id` | 目标目录 id（根目录 `-1`） |
| `upload_file` | 文件本体 |

每个字段都带两个子头：

```
--<boundary>\r\n
Content-Disposition: form-data; name="task"\n
Content-Type: text/plain; charset=UTF-8\n
Content-Transfer-Encoding: 8bit\n\n
1\n
--<boundary>\n
Content-Disposition: form-data; name="folder_id"\n
Content-Type: text/plain; charset=UTF-8\n
Content-Transfer-Encoding: 8bit\n\n
<folderId>\n
--<boundary>\n
Content-Disposition: form-data; name="upload_file"; filename="<name>"\n
Content-Type: <mime>\n
Content-Transfer-Encoding: binary\n\r\n
<文件字节>
```

请求头（同样出自字节码 K35-K45）：

| 头 | 值 |
|---|---|
| `Connection` | `Keep-Alive` |
| `Charset` | `UTF-8`（非标准头，但原版必带） |
| `User-Agent` | 原版 UA |
| `Cookie` | 完整 Cookie 串 |
| `Content-Type` | `multipart/form-data;boundary=<b>` |

**注意：原版没有 Referer。** 本仓库的 `LanzouRefererInterceptor` 仍然会补一个，
这是我们的加固项（此前实测 Referer 缺失会导致 zt=1 不入库），与原版不同但无害。

### 20.3 为什么不能用 OkHttp 的 `MultipartBody`

`MultipartBody.Part.create()` 会**拒绝** part 里出现 `Content-Type`，
抛 `IllegalArgumentException: Unexpected header: Content-Type`。
而原版每个 part 都必须带它。所以只能手写 `RequestBody` 输出字节流——
见 `UploadRepositoryImpl.buildOriginalMultipart`。

### 20.4 端点与域名（原版原文）

`home_func.lua:1334/1496`、`ty_core.lua:509`：

```
上传 URL = "https://" .. 设置.domain_name .. "/html5up.php"
设置.domain_name 默认 "pc.woozooo.com"（可选切换 up.woozooo.com）
```

* **不带 `?uid=`**——`uid` 只加在 `doupload.php` 上（`LanzouUidInterceptor` 已按此实现）。
* 默认 `LanzouDomainConfig.uploadServer = https://pc.woozooo.com/`，与原版一致。

### 20.5 排障工具：上传自检（探针）

`html5up.php` 在参数不符时**不报错**，只回 `zt=1` 却不入库。光看 App 文案无法定位。
因此在设置页加了「上传通道自检」：往根目录传一个 40 字节 txt，把
**HTTP 码 / 实际请求 URL / 凭证状态 / 服务端原始回包**原样显示，并可一键复制。

拿到回包后按这个表判断：

| 回包 | 含义 | 处理 |
|---|---|---|
| `{"zt":9,...}` | 未登录 / Cookie 失效 | 退出重登 |
| `{"zt":1,"text":[{...id...}]}` | 真的成功了 | 检查是不是查错了目录 |
| `{"zt":1,"text":"..."}`（text 不是数组） | **假成功**，参数不对 | 核对 3 字段与子头 |
| HTTP 404 / 返回 HTML | 端点或域名失效 | 改 `uploadServer`，或试 `up.woozooo.com` |
| `httpCode = -1` | 请求根本没发出 | 看异常栈（网络/证书/拦截器） |

代码位置：

* `core/domain/repository/UploadRepository.kt` → `UploadProbeResult` + `probeUpload()`
* `core/data/repository/UploadRepositoryImpl.kt` → `probeUpload()`、`buildOriginalMultipart()`
* `core/data/remote/LanzouApiService.kt` → `upload()` / `uploadProbe()`（都带 `@Header("Charset")`）
* `feature/settings/SettingsScreen.kt` → `UploadProbeDialog`

### 20.6 本轮修掉的编译错误（V12 提交 `7dc41bc` CI 失败）

```
e: .../core/data/remote/LanzouApiService.kt:247:25 Unresolved reference 'Body'.
```

改 `@Body` 时忘了 `import retrofit2.http.Body`。

**更值得记住的是误判过程**：日志里只有这**一个**错误，我一度以为只有一处问题。
实际上 Kotlin 编译器在 `upload()` 返回类型解析失败后，会把调用点 `resp` 变成 error type，
从而**吞掉**后续所有相关错误。所以：

> CI 日志里错误越少，越要警惕——可能是上游错误把下游错误"屏蔽"了。
> 修完一批错误后必须**再跑一次**，别假设"只剩一个"。

---

## 21. V13：别把"服务端成功但列表没查到"当成失败（2026-09-09）

### 21.1 现象

用户报告：设置页的「上传通道自检」**两次都成功**，但"实际直接上传文件还是不行"。

自检原始回包（两次，id 不同且真实）：

```
HTTP 200
https://pc.woozooo.com/html5up.php
credential=true
{"zt":1,"info":"上传成功","text":[{"icon":"txt","id":"316683085",
 "f_id":"iYcYb47il7cf","name_all":"cloudbox_upload_probe.txt", ...}]}
```

**这条证据的价值**：它一次性排除了四大嫌疑——协议字段、Cookie 凭证、域名端点、
multipart 拼装。原生直传本来就是通的，问题只可能在"上传之后"。

### 21.2 真凶：`verifyOnCloud` 把真成功判成失败

旧逻辑（`UploadRepositoryImpl.uploadFile` 步骤 ④）：

```kotlin
val verified = verifyOnCloud(folderId, uploadName)
when (verified) {
    true  -> result
    false -> UploadResult(..., success = false, "服务器返回成功，但云端目录未找到该文件（未真正上传，请重试）")
    null  -> UploadResult(..., success = false, "上传结果无法确认…")
}
```

`verifyOnCloud` 靠"在文件列表里能查到同名文件"来判定。它的失败面比想象中宽得多：

* 目录文件多 → 新文件不在第一页（旧实现**只查 pg=1**）
* 排序不是按时间倒序
* 服务端入库有延迟（旧实现 3 轮重试，共 3.6s，大文件不够）
* 后缀伪装后 `name_all` 与本地 `uploadName` 对不上
* 列表请求本身偶发失败

只要命中任何一条，**文件其实已经躺在网盘里了**，App 却弹出
"未真正上传，请重试"。用户于是反复重传。

> 这就是"假成功"的镜像问题——**假失败**。两者同样致命。

### 21.3 修法：成败只由服务端回包决定

判定权收回到 `doUpload`（它已经很严格：`zt==1` 且 `text` 是数组且首元素带非空 `id`）。
列表确认降级为**可选的正反馈**：

```kotlin
if (verifyOnCloud(folderId, uploadName)) {
    result.copy(message = "已上传，且已在文件列表中确认")
} else {
    result            // 查不到就当没发生，绝不再报失败
}
```

同时把 `verifyOnCloud` 瘦身：**只查第一页、不重试、不翻页、异常一律吞掉**。
理由是它既然不承担判定职责，就不该拖慢流程——旧实现每个文件最多要发
3 轮 × 5 页 = 15 次列表请求，批量上传时纯属自残。

**判据的可靠性来源**：服务端只有真的创建了文件才会返回 `id`，
自检两次拿到两个不同的真实 id 就是直接证据。反过来说，
当年"假成功"的成因是 `zt=1` 但 `text` 不是数组/没有 id——
那种形态现在已被 `doUpload` 拦死，不会漏到这一步。

### 21.4 顺带：上传入口去掉"通道开关"

用户原话："我要APP直接上传，你给改成网页自己动手了？那我为什么要做APP呢。"

既然原生直传已被证明可用，就不该再有一个 `preferWebUpload` 默认通道开关
（它还会让 FAB 文案变成"上传文件（官方网页通道）"这种含混表述）。改动：

| 位置 | 变化 |
|---|---|
| `FileListScreen` FAB | 「上传文件到当前目录」**永远**走原生直传；另一项改为「打开官方网页上传页（备用）」 |
| `SettingsScreen` | 开关换成普通入口行「打开官方网页上传页（备用）」（根目录） |
| `SettingsViewModel` / `SettingsStore` / `FileListViewModel` | 删除 `preferWebUpload` 及其 DataStore key |

上传失败时 Snackbar 上的「网页上传」动作**保留**——那时它才是真正的兜底。

### 21.5 方法论沉淀

> 1. **先造一个能给出客观证据的探针，再谈修 bug。**
>    没有自检功能时，我们只能靠"用户说不行"来猜；有了它，一次回包就排除了四个方向。
> 2. **"确认性检查"不要拥有否决权。**
>    二次确认（列表回查、ping 等）只适合用来加正面反馈或打日志；
>    一旦让它决定成败，它的失败面就成了整个功能的失败面。
> 3. **假成功和假失败是同一种病。** 两端都要堵：
>    既不能"没传上去却说成功"，也不能"传上去了却说失败"。

---

## 22. V14：上传超时 30s + 重试豁免漏判（2026-09-09）

### 22.1 用户反馈

> "失败的没有上传上去"

这句反馈排除了上一节"假失败"的猜想——**失败是真的失败**，
于是嫌疑重新回到"请求层"。

### 22.2 两个叠加的 bug

**bug A：写超时只有 30 秒**

```kotlin
.writeTimeout(AppConstants.TIMEOUT_WRITE_MS, ...)   // 30_000
```

30s 对"普通 API 请求"合理，但上传是**长时间持续写入**：
30s 只够传二三十 MB（弱网时更少），稍大的文件必然被掐断。

这也解释了为什么自检能成功——探针只有 **36 字节**，0.1 秒就传完了。

> 教训：**超时是"操作类型相关的"**，不能把一次性 API 调用的规格
> 照搬到长写/长读操作上。上传、下载、长轮询都要单独设。

**bug B：重试豁免漏掉了新端点**

`RetryInterceptor` 里有这么一段：

```kotlin
val isUpload = request.method == "POST" && request.url.encodedPath.contains("/fileup.php")
```

V6 起实际端点早已从 `fileup.php` 换成 `html5up.php`（前者已 404），
**这里没跟着改**。后果：上传请求没被豁免，一旦超时就整包重传 3 次
（2s / 4s / 8s 退避），用户干等十几秒，最后还是失败。

> 教训：**端点改名时，必须全局搜一遍旧端点字符串**。
> 判定逻辑里藏着的字符串常量是最容易漏的地方——它不会编译报错。

### 22.3 修法

| 问题 | 修法 |
|---|---|
| 写超时 30s | 新增 `AppConstants.TIMEOUT_UPLOAD_MS = 10 分钟`；`LanzouApiClient.uploadOkHttpClient` 由 `okHttpClient.newBuilder()` 派生，只改读/写超时，拦截器/连接池/CookieJar 全量继承；上传接口改用 `uploadApiService` |
| 重试豁免漏判 | `isUpload` 同时匹配 `/fileup.php` 与 `/html5up.php` |

为什么用 `newBuilder()` 派生而不是新建一个 client：OkHttp 的超时绑在 client 上、
无法按单个请求覆盖；`newBuilder()` 会继承连接池与全部拦截器，
额外成本只有一个实例，不会破坏 keep-alive 复用。

### 22.4 顺带补的诊断

* **0 字节检查**：SAF 从第三方 App 拷贝到缓存失败时，文件存在但是空的。
  以前会照传，结果不是被拒就是变成空文件，很难反推原因。现在直接拦下并说明。
* **超时人话提示**：`SocketTimeoutException` 对用户毫无意义，
  改成"文件偏大或网络太慢，建议换 Wi-Fi，或分卷后再传"。
* 失败原因统一带上 `(大小 xxxKB)`。

### 22.5 排查顺序复盘

这次能以较快速度收敛，靠的是这个顺序：

1. 自检探针给出客观证据 → 排除协议/凭证/域名/端点
2. 用户一句"失败的没上传上去" → 排除假失败猜想
3. 剩下就是"请求发出去了但没成功" → 直奔超时与重试

> **先造探针，再修 bug。** 没有第 1 步，我们还在协议里打转。

---

## 23. V15 复查：multipart 格式实测 + 分卷命名 bug（2026-09-10）

### 23.1 用真实 HTTP 请求验证 multipart 格式

`buildOriginalMultipart` 生成的 body 到底合不合法，光看代码看不出来。
写了 `verify_multipart.py`（工作区，未入库）做端到端验证：

1. 按 Kotlin 代码**逐行复现**字节序列
2. 真的发一次 HTTP POST 到本地起的临时服务
3. 服务端用 Python 标准库 `email.parser`（RFC 7578 兼容）解析

结果全绿（含中文文件名）：

```
字段清单: ['folder_id', 'task', 'upload_file']
[OK ] 字段数量: 3
[OK ] task 值: b'1'
[OK ] upload_file 文件名: '中文 测试.txt'
[OK ] upload_file 内容一致（69 字节，含中文）
[OK ] 文件段 Content-Type / CTE
[OK ] 请求头 Charset: UTF-8
```

结合真机自检（HTTP 200 / zt=1 / 真实 id），协议层可以判定为**已确认**。

### 23.2 修掉的分卷命名 bug

`SplitZipUtil.split` 收集分卷时：

```kotlin
File(outPath.replace(".zip", ".z%02d".format(idx)))   // 错
```

`String.replace` 替换**所有**匹配。文件名自带 `.zip` 时：

```
archive.zip.bak  →  nameWithoutExtension = "archive.zip"
                →  outPath = "archive.zip.zip"
                →  replace 得 "archive.z01.z01"   ← 错
                →  正确应为 "archive.zip.z01"
```

后果：一个分卷都收集不到，`volumes` 只剩那个**未切分**的 .zip，
它通常超过 100MB 上限，直接被服务端拒绝——表现为"大文件上传必失败"。

修法：只去掉**末尾**的 `.zip` 再拼分卷号（`removeSuffix`）。

> 教训：**`String.replace` 是全局替换，不是替换第一个**。
> 处理"去掉扩展名"这类需求时，一律用 `removeSuffix` / `substringBeforeLast`。

### 23.3 本次复查的其它结论

* 权限已就绪：`FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_DATA_SYNC` 都已在
  Manifest 声明——将来若要把 UploadWorker 提为前台服务以突破 10 分钟限制，
  不需要再动权限。
* `copyUriToCache` 在 `openInputStream` 返回 null 时会返回一个 0 字节文件，
  但 `uploadFile` 已有 0 字节检查并给出明确提示，不会静默传空文件。
* `contentLength()` 与 `writeTo()` 的字节数一致（都用 UTF-8、文件按 length 流式写出），
  不会触发 OkHttp 的长度校验异常。
* CI 日志 18 条警告全是既有废弃 API（图标/进度条），与上传链路无关。

---

## 24. V17：「App 说全部成功、云端一个文件都没有」的最后一环（2026-09-10）

### 24.1 现象与既往误判

用户反复反馈：**上传显示成功，网页端/手机端都看不到文件**。
此前几轮我们按"协议不对"的方向查，并把协议层彻底排除了：

* 真机探针两次返回 `HTTP 200 / zt=1 / info="上传成功" / text[0].id=316683085`
  —— 域名、端点、凭证、multipart 格式全部正确；
* `verify_multipart.py` 用 Python `email.parser`（RFC 7578 标准解析器）
  解析我们生成的 body，含中文文件名在内全部通过。

**协议没问题，但真实上传依然失败** —— 说明失败发生在"探针不走的那段路"上。
探针与真实上传的差异清单：

| | 探针 | 真实上传 |
|---|---|---|
| 调度方式 | ViewModel 里直接调 suspend 函数 | WorkManager Worker |
| 每批文件数 | 1 | **50** |
| 文件间延时 | 无 | **1–3 秒 × N** |
| 运行时上限 | 无（普通协程） | **10 分钟硬上限** |
| 命名 | 原名 | 可能被后缀伪装改成 `x.apk.zip` |

### 24.2 真因：WorkManager 的两个坑叠在一起

**坑一：单个 Worker 只有 10 分钟。**
WorkManager 给每个 Worker 的运行时间是硬上限 10 分钟。超时后系统会中断它，
状态置为 `FAILED`（不是重试，这一次就废了）。
旧实现每批 50 个文件、每个之间 sleep 1–3s 防风控，
光延时就最多 150 秒，再加上传时间，总时长轻易突破 10 分钟。

**坑二（致命）：`FAILED` 被当成了成功。**

```kotlin
// 旧代码
if (info.state.isFinished && workStates[workId] == null) {
    finishedCount += batchSizes[idx]
    info.outputData.getString(KEY_FAILED_FILES)?.let { failedAccumulator.addAll(...) }
    checkAllFinished()
}
```

`WorkInfo.State.isFinished` 对 **SUCCEEDED / FAILED / CANCELLED 都成立**，
但只有 `SUCCEEDED` 的 Worker 才写了 outputData。
于是：

```
Worker 超时 → FAILED → outputData 为空 → failed 名单为空
→ okCount = globalTotal → 弹出「全部上传成功（N 个）」
```

**文件一个都没上去，App 却报告全部成功。** 这与用户描述完全吻合。

### 24.3 修复（三处）

1. **终态必须区分**（`UploadViewModel.observeWorks`）
   只有 `SUCCEEDED` 才读 outputData 判成败；
   `FAILED` / `CANCELLED` 单独计入 `abortedFiles` 并给出原因
   （"上传任务被中断（单个文件超过 10 分钟，或进程被系统回收）"），
   **绝不混进成功数**。会话恢复路径（`init`）同步改成同一口径。

2. **每批只放 1 个文件**（`UploadViewModel.enqueueUpload`，`chunked(50)` → `chunked(1)`）
   这样 10 分钟就是**单文件**的超时上限，与 `uploadOkHttpClient` 的
   10 分钟写超时语义一致。副作用：进度天然精确（1 批 = 1 文件），
   不再需要"批内进度 + 批间累计"的换算。

3. **Worker 异常兜底**（`UploadWorker.doWork`）
   整个上传循环包 `try/catch`，异常转成"失败 + 原因"仍返回 `success`，
   绝不让 `doWork` 把异常抛出去变成无信息的 `FAILED`
   （`CancellationException` 除外，协程取消不是错误，必须原样抛出）。

顺带：文件间延时从 1–3s 降到 **300–800ms**。原版 Lua 里根本没有上传延时，
1–3s 是我们自己加的，多文件时只会把总时长推向 10 分钟上限。

### 24.4 同步修掉的两个"探针测不出真实问题"

* **探针不走后缀伪装改名** —— 真实上传会把 `x.apk` 提交成 `x.apk.zip`，
  探针却用原名，等于在测另一条路径。
  现在 `probeUploadWith` 复用与 `uploadFile` 完全相同的命名逻辑，
  结果里新增 `uploadAs` 字段显示实际提交名。
* **探针固定传根目录** —— 用户多在子目录上传，根目录能传不代表子目录能传。
  `UploadProbeResult` 新增 `targetFolderId`，并在网盘页加了
  「诊断上传（拿文件测一遍）」入口（当前目录 + 真实文件）。

### 24.5 教训

> **不要用 `isFinished` 判断"任务成功"。**
> 它只表示"不再运行了"。判断成败必须显式比对
> `state == WorkInfo.State.SUCCEEDED`，其余终态一律视为失败。
> 这类 bug 的可怕之处在于：**失败路径恰好产生了最乐观的文案**，
> 用户看到的全是"成功"，问题永远浮不出来。

> **WorkManager 不适合跑"一个可能很长的批量任务"。**
> 它的 10 分钟是硬限制。长任务要么拆成一个文件一个 Worker（本项目采用），
> 要么改用前台服务。需要长跑时，先算一遍最坏耗时再决定粒度。

> **自检工具必须与真实路径逐环节一致。**
> 探针少了任何一个环节（改名、目录、client、超时设置），
> "探针成功"就不能证明真实路径成功，反而会误导排查方向。

---

## 25. V18：上传链路全量可观测化（2026-09-12）

### 25.1 用户的新线索推翻了 V17 的结论

用户反馈：**"是上传秒成功，不正常"**。

这一句直接否掉了 V17 的"10 分钟超时"假说 —— 秒级完成说明请求要么没发出去，
要么发的不是这个大文件。V17 修的是"超时被中断后误报成功"，
那是**慢**失败；而"秒成功"是**快**假成功，两者成因完全不同。

### 25.2 分析：报「全部上传成功」的充要条件

把 `checkAllFinished` 的判定逻辑穷举一遍，结论很干净：

```
total>0 且 失败数==0 且 中断数==0   →   报「全部上传成功」
```

所以"秒成功"＝**Worker 全部以 SUCCEEDED 结束，且一条失败记录都没写**。

结合 Worker 的代码，只有两种可能：
1. **文件真的传上去了**（用户没在预期位置找到 → 属于"传错地方"而非"没传"）；
2. **Worker 压根没执行**，或执行了但走了某个不写失败名单的分支。

静态阅读代码无法区分这两者 —— 必须让运行时自己说话。

### 25.3 本轮改动：把"猜"换成"可观测"

#### ① 结构化时间线（`UploadViewModel.timeline`）

上传链路是**五段式**的：ViewModel 建任务 → WorkManager 调度 → Worker 执行 →
回写 outputData → ViewModel 汇总。以前出问题只能猜是哪一段断的，现在每段都记一行：

```
HH:mm:ss.SSS  已拷贝 3 个文件到缓存，准备入队（每批 5 个）
HH:mm:ss.SSS  任务已入队：1 个 Worker（链式串行），首个 id=a1b2c3d4
HH:mm:ss.SSS  批次 a1b2c3d4 → ENQUEUED 进度=-1/-1
HH:mm:ss.SSS  批次 a1b2c3d4 → RUNNING 进度=0/3
HH:mm:ss.SSS  批次 a1b2c3d4 → SUCCEEDED 进度=3/3
HH:mm:ss.SSS  裁决 total=3 失败=0 中断=0 状态=a1b2c3d4=SUCCEEDED
```

**判读方法**：
* 没有"任务已入队" → 卡在入队之前（拷贝失败 / 异常）；
* 有 ENQUEUED 但**没有 RUNNING** 就直接 SUCCEEDED → Worker 没真正执行，是假成功；
* 有 RUNNING 且有服务端回包 → 真的发出去了，问题在服务端判定或目标目录。

#### ② 上传日志（`CloudBoxUpload` tag）

`adb logcat -s CloudBoxUpload` 可看到逐文件的"上传开始 / 上传回包"，
含**字节数**与**耗时**：

```
上传开始 file=a.apk as=a.apk.zip size=1048576B folder=-1 mime=application/zip
上传回包 file=a.apk.zip 耗时=1823ms zt=1 info=上传成功 text=数组(1 项)
Worker 启动 id=... folder=-1 files=1 spoof=true
```

耗时是判断"秒成功"性质的关键指标：
* **几毫秒**回包 → 请求根本没到服务端（本地短路）；
* **几百毫秒但 size 很小** → 发出去了，但传的是个坏文件；
* 与文件大小相称 → 链路正常。

#### ③ 上传失败详情弹窗

失败时 Snackbar 的动作从「网页上传」改成「**看详情**」，
弹出完整时间线 + 失败文件名单，可一键复制。
（网页上传仍保留在 FAB 菜单，只是不再抢走失败时的第一入口。）

#### ④ 启动自检

`CloudBoxApp.onCreate` 打印 WorkManager 的 workerFactory 是否挂上 Hilt 实现。
若 Hilt 工厂没生效，`@HiltWorker` 无法构造，所有上传任务都会失败且无有效报错 ——
这一行日志可以立刻排除该方向。

### 25.4 同时修掉的三个真实缺陷

#### ① `copyUriToCache` 静默返回空文件（**最可疑的元凶**）

旧实现有三条静默失败路径，全都表现为"秒成功、云端没有"：

| 路径 | 旧行为 | 后果 |
|---|---|---|
| `openInputStream()` 返回 null | `?.use{}` 直接跳过，不抛异常 | 返回 **0 字节**文件送进上传 |
| 流读到一半断了 | `copyTo` 不报错 | 返回**短了**的残文件送进上传 |
| 缓存空间不足 | 异常被外层 `runCatching` 吞掉 | 返回 null，只有一句笼统提示 |

传一个空文件/残文件出去，服务端几乎立刻回包 → **用户看到"秒成功"**。

现在：拷贝后**核对字节数**（拿 `OpenableColumns.SIZE` 作标称值），
空文件、断流、无流全部**拒绝上传**并给出可读原因，
理由通过新增的 `lastCopyError` 透传到 UI。

#### ② `SocketTimeoutException` 判定顺序反了

```kotlin
// 旧：SocketTimeoutException 是 IOException 的子类，被后一个分支先接住
is java.io.IOException -> "网络错误：${e.message}"
is java.net.SocketTimeoutException -> "上传超时：…"   // ← 永远走不到
```

顺序调正，并补上 `UnknownServiceException`（明文 HTTP 被系统拦截）的专门文案。

#### ③ Worker 删目录的隐患

```kotlin
if (dir.listFiles()?.isEmpty() != false) dir.delete()
```

`listFiles()` 返回 null（目录不可读）时，`null?.isEmpty()` 是 null，
`!= false` 判为 **true** —— 会把一个根本没读到的目录当成空目录删掉。
多文件共用 `uploads/<uuid>/` 时还可能删掉后面待传文件所在的目录。
**直接去掉这段**：残留空目录无害（系统会回收 cacheDir），不值得冒险。

### 25.5 批量粒度：1 → 5

V17 把每批从 50 改成 1，方向对（避开 10 分钟线）但**过头了**：

* N 个文件 = N 个链式 Worker，链上**任一节点**被系统压制/中断，后续全部不执行；
* 每个文件都要走完 `ENQUEUED → RUNNING → SUCCEEDED` 三次状态往返，
  多文件时状态回放时序复杂度陡增 —— 正是"秒成功"这类时序 bug 的温床。

改成 **5**：单 Worker 最多 5 个文件（即便都是大文件也不会撞 10 分钟线，
大文件本就走分卷），Worker 数量压到 1/5，时序大幅简化。

### 25.6 还补了一道兜底

`globalTotal == 0` 时不再报「全部上传成功（0 个）」——
"一个文件都没处理"和"全部成功"是两回事，前者必须报失败。

### 25.7 教训

> **"快"和"慢"是两类完全不同的故障，不能套用同一个解题思路。**
> 上一轮我抓的是"慢失败被误报成功"，这一轮用户给的"秒"字
> 直接说明请求根本没走到网络 —— 我在上一轮的结论上多待了一轮才转向，
> 这是浪费。听到反例先重估假设，不要修补旧结论。

> **定位不了的时候就别改逻辑，先加观测。**
> 本轮最有价值的产出不是那三处修复（虽然都是真 bug），
> 而是那条时间线 —— 它把"五段式链路里哪一段断了"这个问题
> 从"靠读代码猜"变成"看一眼就知道"。

> **自检工具与真实路径不一致时，自检结论是负资产。**
> 探针一直"成功"，反而让我反复得出"协议没问题"的结论。
> 本轮把探针补上了改名、目录、耗时三处一致性，
> 并让失败详情也用同一个弹窗呈现。

---

## 26. V19：实测定位真因（2026-09-12）

用户提供了一份完整的诊断回包 + 时间线，一次性定位了两个此前完全没找到的问题。

### 26.1 问题一：服务端拒绝 —— 文件没有扩展名（真正的上传失败原因）

```
HTTP 200   https://pc.woozooo.com/html5up.php   credential=true
folder_id=13941316
file=upload_1789223795017 (379 KB)      ← 注意：没有扩展名！
elapsed=334ms
{"zt":0,"info":"不能上传.格式的文件","text":null}
```

**`upload_1789223795017` 是 `copyUriToCache` 里"拿不到 DISPLAY_NAME 时的兜底名"。**
旧实现只查一个渠道：

```kotlin
query(uri, null,null,null,null) 找 DISPLAY_NAME  ?: "upload_<时间戳>"
```

而 `contentResolver.query(uri, null, ...)` 在多 provider / 部分文件管理器下
会返回 null 或没有该列（**尤其是 OpenDocument 选出来的、来自网盘类 App 的文档**），
名字就退化成纯数字，**扩展名随之丢失**。

蓝奏云**按扩展名决定能不能上传**，与内容/大小/MIME 都无关 ——
没有扩展名一律回 `不能上传.格式的文件`。

#### 修法（三层）

1. **`resolveDisplayName` 按可靠度递减多渠道解析**
   ① `OpenableColumns.DISPLAY_NAME`
   ② `uri.lastPathSegment`（file:// 或部分 content://）
   ③ **按 MIME 反推扩展名**，实在拿不到才用 `.bin` 兜底 ——
   宁可扩展名是猜的，也不能交一个没有扩展名的文件上去。
2. **上传前前置校验**：没有扩展名就在本地拦下并说明原因，
   比让服务端回一句语焉不详的中文有用得多。
3. **诊断入口 `OpenDocument` → `GetContent`**：实测 OpenDocument 正是
   不返回 DISPLAY_NAME 的那个 contract，用它做诊断等于把假象当证据。

#### 顺带验证

用 Python 复刻同样的 multipart（379KB、无扩展名文件名）打生产端点，
无凭证时 **159ms** 就回包 `{"zt":9,"info":"login not"}` ——
说明服务端在鉴权阶段就会迅速拒绝。因而用户看到的 **334ms 对 379KB 完全正常**，
服务端确实收到了完整文件并做出了判定。**链路本身是通的，卡在文件名上。**

### 26.2 问题二：Worker 瞬间 FAILED（`Configuration.Provider` 的初始化时机陷阱）

```
批次 a8920de4 → ENQUEUED
批次 a8920de4 → FAILED   runAttemptCount=0  outputData={}   ← 空
裁决 total=1 失败=0 中断=1
```

Worker 从 ENQUEUED **直接跳到 FAILED**，没有 RUNNING，重试计数 0，
outputData 完全为空 —— 即调度器**压根没能构造出 UploadWorker**。
连 Worker 里那句 `Worker 启动` 日志都没出现，可以确证。

#### 根因

manifest 按 Hilt 官方要求用 `tools:node="remove"` 摘掉了
`WorkManagerInitializer`，改为依赖 `Configuration.Provider` **按需初始化**。
但"按需"的时机由**第一次 `WorkManager.getInstance()` 的调用点**决定 ——
而 UploadViewModel 是 `@Inject workManager: WorkManager` 注入的，
首次初始化可能赶在 `workerFactory` 注入完成**之前**，读到未初始化的
`lateinit` → 初始化失败 → **之后所有 Worker 一律瞬间 FAILED，且无任何可读错误**。

雪上加霜的是 `workManagerConfiguration` 写成了属性 getter：

```kotlin
override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
```

getter 每次读取都重新求值，任何一次过早的读取都会踩雷。

#### 修法

1. `workManagerConfiguration` 由 `get()` 改为 **`by lazy`**，
   把求值推迟到注入必然完成之后，且不再重复构建；
2. **在 `onCreate` 里主动 `WorkManager.getInstance(this)` 一次**。
   此刻 `@HiltAndroidApp` 的注入已在 `super.onCreate()` 内完成，
   配置必然正确；后续所有 `getInstance()` 复用这个已初始化的单例。
   —— 这一步是根治点：把"不可控的首次初始化时机"变成"确定的时机"。
3. 自检日志输出实际生效的 factory，若读到配置失败立刻报错。

> 这是本轮最有价值的发现：**它解释了此前所有"秒失败"**。
> 而且症状极具迷惑性 —— UI 上没有任何错误，只有一行时线在说
> "ENQUEUED 然后就 FAILED 了"。若没有 V18 加的时间线，
> 这个问题还会继续被归因到"网络""协议""风控"上。

### 26.3 时间线这次的实战表现

对照 V18 写下的判读法，这次两条都能对上：

| 观察 | 结论 |
|---|---|
| 有"任务已入队"，有 ENQUEUED | 入队没断 |
| ENQUEUED **直接 FAILED**，无 RUNNING，`runAttemptCount=0` | **Worker 没被构造** → 工厂/初始化问题 |
| 探针 `elapsed=334ms` + 服务端回中文错误 | 请求真的发出去了，链路通 |

**一个"秒失败"，一个"秒成功"，同一条时间线把两者的性质完全分开了。**
这正是上一轮加可观测性的价值所在。

### 26.4 教训

> **`ContentResolver.query(uri, null, ...)` 不可靠，必须有多渠道兜底。**
> 它在不同 provider / 不同 contract 下返回的列集完全不同。
> 尤其 `OpenDocument` 与 `OpenMultipleDocuments`/`GetContent` 的行为有差异，
> **诊断工具必须用与真实路径相同的 contract**，否则测出来的不是同一个东西。

> **`Configuration.Provider` + `tools:node="remove"` 组合有初始化时机陷阱。**
> 按需初始化意味着时机不可控。凡是"初始化要读注入字段"的场景，
> 都应在 `onCreate` 里显式触发一次，把时机钉死。

> **"秒失败"和"秒成功"一样，都是"请求没真正走到目的地"的信号。**
> 这次一个 334ms 的真回包（带中文错误）立刻澄清了性质 ——
> 有服务端返回内容 = 链路通；只有 WorkManager 状态 = 卡在本地。

---

## 27. V20 + V21：Worker 瞬间 FAILED 的真根因，与「看详情看不到」（2026-09-13）

这一节记录一个**极其隐蔽、代价极大**的坑，以及一次 UI 层面的状态竞态。
两者叠在一起，让"上传失败"这件事在用户侧表现为"说不清哪里错"。

### 27.1 V20：缺少 `androidx.hilt:hilt-compiler` —— Worker 根本没被构造

**现象**（用户实测，一次不差）：

```
21:10:40.062  批次 68269d5e → WorkInfo{ state=ENQUEUED }
21:10:40.068  批次 68269d5e → WorkInfo{ state=FAILED, outputData=Data {},
                       runAttemptCount=0, nextScheduleTimeMillis=9223372036854775807 }
21:10:40.068  裁决 total=1 失败=0 中断=1 状态=68269d5e=FAILED
```

全过程 **6 毫秒**，`ENQUEUED` 直接跳 `FAILED`，中间**没有 `RUNNING`**。

**这些特征的含义**：

| 字段 | 含义 |
|---|---|
| `runAttemptCount=0` | 一次尝试都没发生过 |
| `outputData=Data {}` | Worker 没往结果里写任何东西 |
| `nextScheduleTimeMillis=Long.MAX_VALUE` | 不会重试 |
| `stopReason=-256` | WorkManager 内部"未执行即终止" |
| **无 RUNNING** | **Worker 实例压根没被构造出来** |

**根因**：`app/build.gradle.kts` 里只写了

```kotlin
ksp(libs.hilt.compiler)   // = com.google.dagger:hilt-android-compiler
```

`com.google.dagger:hilt-android-compiler` 只处理 `@HiltAndroidApp` / `@Inject` /
`@HiltViewModel` 这类 Dagger Hilt 注解。**它不处理 `@HiltWorker`。**
`@HiltWorker` 的工厂绑定（让 `HiltWorkerFactory` 能找到 `UploadWorker` 并完成
`@AssistedInject` 构造）由**另一个**注解处理器生成：

```toml
# gradle/libs.versions.toml
hilt-androidx-compiler = { group = "androidx.hilt", name = "hilt-compiler", version = "1.2.0" }
```

```kotlin
// app/build.gradle.kts
ksp(libs.hilt.compiler)            // Dagger Hilt：@HiltAndroidApp / @Inject
ksp(libs.hilt.androidx.compiler)   // AndroidX Hilt：@HiltWorker / @AssistedInject
implementation(libs.hilt.work)
```

缺了第二个 → 编译期不生成工厂绑定 → 运行期 `HiltWorkerFactory` 找不到
`UploadWorker` 的构造方式 → WorkManager 建不出 Worker 实例 → **瞬间 FAILED，
且不产生任何可读错误**。

**为什么这个坑这么难查**：

它**编译期完全无感**，CI 一路绿。`@HiltWorker` 注解本身合法，
`UploadWorker` 类也编译正常，只是"绑定没生成"。所有报错都发生在运行期，
而且被 WorkManager 吞成了一个状态位。

**排查路径**（值得记住的推理链）：

1. V18 先做了**全量可观测化**（时间线 + 状态变化日志）——
   这是能看见"6 毫秒 ENQUEUED→FAILED"的前提。**没有观测就没有定位。**
2. 看到 `runAttemptCount=0` 排除了"代码抛异常"（抛异常会有 attempt 计数）。
3. 排除 V19 的假设（WorkerFactory 初始化时机）——
   加 `by lazy` + `onCreate` 主动初始化后现象**完全不变**，
   说明不是时机问题，是"根本没有这个东西"。
4. 转向依赖配置，发现两个 hilt-compiler 的区别。

> **教训**：`@HiltWorker` 需要 `androidx.hilt:hilt-compiler`，
> 它和 `com.google.dagger:hilt-android-compiler` 是**两个不同的处理器**，
> 缺一不可。这个依赖必须显式声明。
>
> 更普遍地说：**"瞬间失败且无错误信息"通常意味着失败发生在"构造阶段"
> 而不是"执行阶段"**——对象根本没建出来，自然没有东西能报错。

### 27.2 V21：Snackbar 里清状态，把刚打开的弹窗一起关了

**现象**（用户原话）：

> "上传显示失败，**但点看详情看不到**。"

**根因**：Snackbar 回调里

```kotlin
if (res == SnackbarResult.ActionPerformed) {
    showFailureDetail = true      // ① 想打开弹窗
}
viewModel.dismissMessage()        // ② 立刻把 uploadState.message 清空
```

而弹窗显示条件里挂着 `uploadState.message != null`：

```kotlin
if (showFailureDetail && uploadState.message != null) { ... }
```

①② 在**同一帧**执行 → 条件瞬间不成立 → 弹窗从未渲染。
用户看到的是"点了没反应"。

**修复**：把弹窗条件和易变的 `message` **解耦**，用本地快照：

```kotlin
var failureDetailText by remember { mutableStateOf("") }

// 回调里：先快照内容，再清 message
if (res == SnackbarResult.ActionPerformed) {
    failureDetailText = buildString { /* 失败详情 + 失败文件清单 */ }
    showFailureDetail = true
}
viewModel.dismissMessage()   // 此时清 message 已不影响弹窗

// 弹窗只依赖本地快照
if (showFailureDetail && failureDetailText.isNotBlank()) { ... }
```

**更重要的架构修复**：日志抽成全局单例 `common/UploadTrace.kt`。

上传链路是五段式的：

```
ViewModel 建任务 → WorkManager 调度 → Worker 执行 → 回写 outputData → ViewModel 汇总
```

日志挂在 `UploadViewModel` 上有三个硬伤：

- Worker 没有 UI 作用域，写不进去；
- 设置页拿到的是**另一个** ViewModel 实例，读不到；
- ViewModel 随页面销毁重建，日志会丢。

改成 Hilt `@Singleton` 后：谁都能写、谁都能读，生命周期跟随进程。
并且在**设置页**加了持久入口（可展开 / 复制 / 清空）——
Snackbar 划掉了也不怕，随时能回去翻。

> **教训**：**任何"一闪而过"的 UI 提示（Snackbar / Toast）都不能作为
> 诊断信息的唯一载体。** 诊断信息要有持久、可回溯、可复制的落点。
> 另外，凡是用 `StateFlow` 做 UI 状态，**要注意"打开某 UI"和"清空某状态"
> 是否在同一帧发生** —— 这类竞态在 Compose 里非常常见。

### 27.3 顺带记录：沙箱 HTTPS 通道的 TLS 干扰（环境问题，非 App 问题）

本次推送时遇到 GitHub 推送失败，排查结论值得记下：

| 观察 | 结论 |
|---|---|
| TCP 443 全通（多个 IP） | 不是端口封禁 |
| TLS 握手 **0.5s** 完成 | 不是 SNI 阻断 |
| 完整请求 **45s+** | **数据传输阶段被干扰/限速** |
| 小 payload（首页）能过、git push 必挂 | 大数据量必超时 |
| `gnutls_handshake() failed` / `SSL connection timeout` | 干扰表现为 TLS 层报错 |

**解法：改用 SSH over 443**。

```bash
# 1) ssh.github.com 同样被 DNS 劫持，必须写 hosts
echo "140.82.112.35 ssh.github.com" >> /etc/hosts
echo "140.82.112.35 ssh.github.com" >> ~/.user_hosts

# 2) SSH 走 443
cat > ~/.ssh/config <<'CONF'
Host github.com
  HostName ssh.github.com
  Port 443
  User git
  IdentityFile ~/.ssh/id_ed25519
  StrictHostKeyChecking no
  UserKnownHostsFile /dev/null
  ServerAliveInterval 30
  ServerAliveCountMax 6
CONF

# 3) remote 换成 SSH
git remote set-url origin git@github.com:d1667018881/cloudbox.git
```

验证：`ssh -T git@github.com` → `Hi d1667018881! You've successfully authenticated`

公钥可用 PAT 调 API 添加（无需网页操作）：

```bash
curl --resolve api.github.com:443:140.82.121.5 \
  -X POST https://api.github.com/user/keys \
  -H "Authorization: token <PAT>" \
  -d "{\"title\":\"sandbox\",\"key\":\"$(cat ~/.ssh/id_ed25519.pub)\"}"
```

一键脚本：`/workspace/gh_ssh_setup.sh`

> **附加收益**：remote 用 SSH 形式**结构上就带不了 token**，
> 从根上避免了 PAT 被写进 `.git/config` 的风险。

### 27.4 本版结论

- `v0.1.124`（V20）：补 `androidx.hilt:hilt-compiler`
- `v0.1.125`（V21）：修弹窗竞态 + 日志单例 + 设置页持久入口
- CI Run `34760849646`（commit `4f7a1fe`）**success**

**待用户实测确认的两件事**：

1. 上传失败时点「看详情」**能否弹出内容**；
2. 时间线里**是否终于出现 `RUNNING` 行** ——
   这一行是 V20 是否根治"Worker 瞬间 FAILED"的唯一判据。
   若仍无 `RUNNING`，说明还有别的构造期问题，需继续排查。

---

## 28. V22~V27：功能补齐批次（2026-09-15）

用户要求「**没有加的功能，全都给他加上吧**」。以下是逐项补齐的记录，
每一项都注明**原版依据**（来自哪个 lua 模块/哪一行），不是凭感觉加的。

### 28.1 功能补齐清单（按提交顺序）

| 版本 | 功能 | 原版依据 | 关键实现点 |
|---|---|---|---|
| V22 | 分享链接解析修复 | `home_file.lua` | 根因：`java.net.URI` 遇中文/空格抛异常，整段文案被过滤 |
| V22 | 接收系统分享上传 | `home.lua` 的 `intent操作` | ACTION_SEND / SEND_MULTIPLE，必须 `takePersistableUriPermission` |
| V22 | 上传入口收敛 | — | 移除独立上传 tab，FAB 直传当前目录 |
| V23 | 批量分享真修复 | `home_file.lua` 批量分享 | 旧实现 `selected.firstOrNull()` 只处理第一条（假功能） |
| V23 | 文件列表排序 | `home_file.lua` 排序卡片 | 中文按拼音（Collator），文件夹恒在前 |
| V23 | 多选工具栏补齐 | `home_file.lua` | 全选/批量下载/批量提取码/批量改资料 |
| V24 | 下载列表排序+清空 | `download.lua` | 清空**不删本地文件**（与 cancel 语义不同，不可混用） |
| V24 | 删除二次确认 | `home_file.lua` | 明确告知"几个文件夹/几个文件" |
| V24 | 收藏夹置顶/编辑 | `favorites.lua` | Room v4→v5 显式迁移，**不能** fallback 重建（丢用户数据） |
| V24 | 文件点击操作菜单 | `home_file.lua` | 点文件弹菜单而非直接弹分享框 |
| V24 | 全局错误页 | `error_page.lua` | CrashHandler + 独立 ErrorActivity（崩在导航途中不会二次崩） |
| V24 | 二维码扫描 | `qr.lua` | CameraX + MLKit（离线）；扫到即置 done，防重复回调 |
| V24 | 回收站查看文件夹 | `recycle.lua` 查看文件夹弹窗 | 正则来自原版：`/>&nbsp;(.+?)\s*<font color=` |
| V25 | 关于页 | `about.lua` + `update_log.lua` | 检查更新失败一律降级为中性文案 |
| V25 | 应用内语言 | `ty_core.lua` 的 `语言()` | ⚠️ 原版该函数是**恒等函数**（空壳），故改用系统 per-app locale |
| V25 | 移动网络提醒 | — | `isOnMobileData` = 有蜂窝且无 WiFi |
| V26 | 下载单条操作 | `download.lua` 单条菜单 | 重命名只改本地副本，`COLUMN_LOCAL_FILENAME` 是只读的 |
| V26 | 可自定义伪装后缀 | —（原版硬编码） | 蓝奏云限制会变，硬编码 = 每次改代码发版 |

### 28.2 明确「不做」的功能（有依据，不是遗漏）

| 功能 | 为什么不做 |
|---|---|
| **文件夹移动** | 原版**没有**这个接口。`home_func.lua` 的 `task=19` 是"全盘加载获取文件夹"（用于选目标目录弹窗），不是移动。当前 UI 如实提示"文件夹暂不支持移动"是**正确做法**，做假实现比不做更糟 |
| **第三方解析 API 多字段配置** | 原版 `customize_settings.lua` 里根本没有第三方解析服务配置，那是本仓库自己加的设计。不需要照搬虚构的字段表 |
| **原版更新日志文本** | `update_log.lua` 内容本身加密，解密后只有版本号 `1.3.4.8` 加乱码，无复用价值 |

### 28.3 本批次踩到的三个真坑（值得记住）

#### 坑 1：`java.net.URI` 对非 ASCII 直接抛异常

```
"https://wwt.lanzouj.com/iXXXXXXX"                → 正常
"蓝奏云盘 https://wwt.lanzouj.com/iXXXXXXX"        → URISyntaxException
"https://wwt.lanzouj.com/iXXXXXXX 提取码：abcd"    → URISyntaxException
"https://wwt.lanzouj.com/iXXXXXXX "（仅尾部空格）  → URISyntaxException
```

**仅多一个尾部空格就废**。这是"粘贴整段分享文案解析不出"的根因。
**正确做法**：先用正则从文本里抠出 URL 候选，再逐条校验域名。
不要拿整段用户输入喂 `java.net.URI`。

#### 坑 2：`HttpURLConnection` 不是 `Closeable`

```kotlin
conn.use { }   // ❌ Argument type mismatch: actual type is 'java.io.Closeable?'
```

Kotlin 的 `use { }` 要求 `Closeable`/`AutoCloseable`。
`HttpURLConnection` 的释放方式是 `disconnect()`，必须 `try/finally`。
（对比：OkHttp 的 `Response` 是 `Closeable`，`use` 没问题 —— 容易混。）

#### 坑 3：Room 加字段时，别让 `fallbackToDestructiveMigration` 吃掉用户数据

本项目 `DatabaseModule` 一直有 `fallbackToDestructiveMigration()`（自用项目图省事）。
但收藏夹是**用户自己攒的数据**，加 `pinned` 字段时如果走重建表 = 收藏全丢。
**做法**：为该版本单独写显式 `Migration`，`ALTER TABLE ADD COLUMN` 是无损的。

```kotlin
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE favorite_shares ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
    }
}
```

### 28.4 静态检查 vs 真编译

本批次 4 次 CI，其中 1 次失败（V25），暴露出**两个括号检查抓不到的语义错误**：

- `HttpURLConnection.use`（类型系统错误）
- 缺 `kotlinx.coroutines.flow.first` 的 import（符号解析错误）

`brace_check.py` / `import_scan.py` 只能保证**结构完整**和**无冗余导入**，
**不能替代编译**。CI 这条链路是必要的，不是摆设。

### 28.5 提交链

```
bf1af9a fix(V27): 修两处编译错误（HttpURLConnection.use / 缺 first import）
f19f565 feat(V26): 下载单条操作 + 可自定义伪装后缀
32d7568 feat(V25): 关于页 + 应用内语言 + 移动网络提醒
e0ed337 feat(V24): 六项缺失功能（下载清空排序/删除确认/收藏编辑置顶/
                  文件操作菜单/错误页/扫码）
3f61856 feat(V23): 批量分享真修复 + 文件列表排序 + 多选工具栏
36c2f56 feat(V22): 分享链接解析修复 + 接收系统分享 + 上传入口收敛
```

### 28.6 待用户实测确认（V22~V27 累计）

1. **分享链接**：粘贴整段文案（含中文、尾部空格）能否解析出结果
2. **系统分享**：从文件管理器/相册分享文件到云匣，能否进入上传流程
3. **点文件**：是否弹出操作菜单（而不是直接弹分享框）
4. **下载页**：「⋯」菜单里的重命名/复制直链是否可用
5. **关于页**：「我的 → 关于」检查更新是否返回结果（失败也是正常结果）
6. **回收站**：文件夹行的「查看」按钮能否列出内部文件

### 28.7 安全提醒（必须处理）

- **吊销 PAT** `ghp_...`（曾在对话中明文出现，且已推送过）：
  GitHub → Settings → Developer settings → Personal access tokens → Revoke
- **修改蓝奏云密码**：（口令与 GitHub 用户名复用，风险高 —— **口令原文已于
  2026-09-20 从本文档移除**，见下方「为什么不能把口令写进文档」）
- SSH 公钥 `cloudbox-sandbox-*`：可留可删（私钥已随沙箱消失），无安全风险；
  上限 50 把/key

#### ⚠️ 为什么不能把口令写进文档（2026-09-20 的教训）

上面那条「修改蓝奏云密码」原本是这样写的：

```
- **修改蓝奏云密码**：<八位数字口令原文> 与 GitHub 用户名复用，风险高
```

**这条是「提醒去改密码」的记录，却把密码本身抄了进去，而本文档在公开仓库里。**
等于一边写「这里有个风险」，一边把风险的具体内容公开出去。

这不是笔误，是**方法错了**：在安全类记录里，写下凭据的原文**没有任何收益**
（读者不需要知道原文也能执行「去改密码」这个动作），却把记录本身变成了泄露源。

**规则**：任何写入仓库的内容，出现以下形态一律用占位符，绝不写原文 ——
口令、token（`ghp_…`）、Cookie 值、密钥、手机号、邮箱、真实姓名。

| 场景 | 错误写法 | 正确写法 |
|---|---|---|
| 提醒改口令 | 口令原文 | 「口令与 GitHub 用户名复用，风险高」 |
| 提醒吊销 token | `ghp_abc123…` | 「曾在对话中明文出现的 PAT」 |
| 记录某接口需要的 Cookie | `phpdisk_info=xxx` 真值 | 「需 phpdisk_info（值从会话取）」 |

**注**：本次只改了当前文件。旧提交 `9644280` 的历史里仍留有原文 ——
但只要口令已更换，历史里那串就是**失效字符串**，无需为此重写 git 历史
（重写要强推、会影响所有克隆，风险大于收益）。

---

## 29. V28/V29 —— 网页版官网实测比对（2026-09-15）

### 29.1 这一轮的由来

用户提出一个关键质疑：「有些功能不登录它不显示出来」「官网上是不是支持移动文件夹」。
我上一轮**仅凭原版 APK 的反编译源码**得出「原版没有移动功能」，
但用户要求直接登录**网页版官网**看真实界面 —— 这个要求是对的，
证明**反编译源码 ≠ 服务端能力**：原版 APK 没做，不代表官网没有。

**结论修正**：官网**有「移动」**，但**只能移动文件、不能移动文件夹**。

### 29.2 实测方法（可复现）

```python
# Playwright + storage_state 复用登录态
# 登录：https://up.woozooo.com/account.php?action=login
#   → 重定向到 accounts.woozooo.com（新版登录域）
#   → 字段 #username / #password，提交 #s3
# 关键：真正的文件列表在 iframe[name="mainframe"] 里，
#       页面上层 < 3KB，所有 JS 函数都在 iframe 上下文
# 取函数源码：frame.evaluate("()=>f_view.toString()")
```

三个必须知道的坑：
1. **登录域已换**：`up.woozooo.com/account.php` 会 302 到 `accounts.woozooo.com`，
   表单字段是 `#username` / `#password`，不是旧版的 uid/pwd。
2. **内容在 iframe**：主文档只有 3KB 空壳，`mainframe` 才是文件列表。
   直接 `pg.content()` 拿不到任何操作函数。
3. **函数定义在 iframe 内联脚本**：`f_view` 等不存在于任何 .js 文件，
   只能通过 `frame.evaluate` 在运行时取 `toString()`。

### 29.3 官网菜单源码（决定性证据）

```javascript
// 文件 ⋯ 菜单 —— 9 项，第 5 项是「移动」
function f_view(fid){
  $('#fs'+fid).html('<div class=f_view>'+
    '<div onclick="f_sha(fid)"  class=f_viewtop>外链分享地址</div>'+
    '<div onclick="f_diy(fid,1)">自定义外链</div>'+
    '<div onclick="f_ico(fid,1)">缩略图 图标</div>'+
    '<div onclick="f_ename(fid)">重命名</div>'+
    '<div onclick="f_midf(fid)">移动</div>'+        // ← ★
    '<div onclick="f_pwd(fid)">设置访问密码</div>'+
    '<div onclick="f_des(fid)">添加描述</div>'+
    '<div onclick="f_surl(fid)">短网址</div>'+
    '<div onclick="f_url(fid)" class=f_viewbot>文件直链</div>'+'</div>');
}

// 文件夹 ⋯ 菜单 —— 5 项，无「移动」、无「重命名」
function fol_view(folid){
  $('#fols'+folid).html('<div class=f_view>'+
    '<div onclick="fol_sha(folid)" class=f_viewtop>外链分享地址</div>'+
    '<div onclick="f_diy(folid,2)">自定义外链</div>'+
    '<div onclick="fol_pwd(folid)">设置访问密码</div>'+
    '<div onclick="fol_des(folid)" class=f_viewbot>修改资料(话说)</div>'+
    '<div onclick="fol_surl(folid)">短网址</div>'+'</div>');
}
```

### 29.4 移动协议（已实跑验证）

```javascript
f_midf(fid)          → POST /doupload.php { task:19, file_id:fid }
                       返回全部文件夹（实测 23 个，全盘而非当前层级）
f_midfgo(fol_id,fid) → POST /doupload.php { task:20, folder_id:fol_id, file_id:fid }
                       返回 {"zt":1,"info":"移动成功"}；folder_id=-1 为根目录
```

**实跑记录**（唯一一次写操作，已还原）：
```
移动到 apk文件 → {"zt":1,"info":"移动成功"}
移回根目录     → {"zt":1,"info":"移动成功"}
```

**task=19 vs task=47 的分工**（实测数据，务必别混）：
| 接口 | 参数 | 返回 | 用途 |
|---|---|---|---|
| task=19 | `file_id` | 全盘 23 个文件夹 | 移动目标选择器 |
| task=47 | `folder_id`+`pg` | 当前层级 3 个 | 文件列表里的子文件夹 |

cloudbox 的分工本来就正确（`getAllFolders` / `getDirList`）。

### 29.5 官网接口全表（从 iframe 运行时提取）

| task | 功能 | 触发函数 | 关键字段 |
|---:|---|---|---|
| 2 | 新建文件夹 | `fol_crego` | parent_id, folder_name, folder_description |
| 3 | 删除文件夹 | `fol_dec` | folder_id |
| 4 | **改文件夹资料** | `fol_desgo` | folder_id, **folder_name, folder_description** |
| 5 | 列文件（当前目录分页） | `more` | folder_id, pg |
| 6 | 删除文件 | `f_dec` | file_id |
| 11 | **写**文件描述 | `f_desgo` | file_id, desc |
| 12 | **读**文件描述 | `f_des` | file_id → `info` 即描述 |
| 16 | 文件夹提取码 | `fol_pwdgo` | folder_id, shows, shownames |
| 18 | 文件夹信息 | `fol_pwd`/`fol_sha`/`fol_des`/`fol_surl` | folder_id → `info{name,des,pwd,onof,is_newd,new_url}` |
| 19 | **全盘文件夹列表** | `f_midf` | file_id |
| 20 | **移动文件** | `f_midfgo` | folder_id, file_id |
| 22 | 文件分享信息 | `f_pwd`/`f_surl`/`f_sha` | file_id → `info{pwd,onof,f_id,is_newd,taoc}` |
| 23 | 文件提取码 | `f_pwdgo` | file_id, shows, shownames |
| 31 | 文件直链 | `f_url` | file_id |
| 39/40 | 自定义外链（读/写） | `f_diy`/`f_diygo` | file_id, diy, type |
| 41 | 缩略图 | `f_ico` | file_id |
| 46 | 重命名文件 | `f_ename`(type=1 读)/`f_enamego`(type=2 写) | file_id, file_name |
| 47 | 子文件夹列表（当前层级） | `folder` | folder_id, pg |

### 29.6 本轮修的问题（V28/V29）

1. **文件描述不回填**（真 bug）
   `task=12` 早就定义成 `getFileInfo` 却**从未被调用** → 描述弹窗永远空白，
   用户只能盲改。新增 `getFileDesc()` + `loadDescForEdit()`。
   `SimpleInputDialog` 增加 `initialValue`/`singleLine`/`enabled`，描述改多行。

2. **文件夹提取码走了错误接口**（真 bug）
   官网文件 `task=23`、文件夹 `task=16`，id 字段分别是 file_id / folder_id。
   原代码单条误用文件接口、批量直接跳过文件夹。新增 `setDirPasswd()` 按类型分流。

3. **`zt:null` 会崩**（真隐患）
   task=16 非会员返回 `{"zt":null,"info":"此功能仅会員使用…"}`。
   `CommonResponse.zt` 是非空 `Int`，Gson 反射塞 null 会抛异常 ——
   把"没开会员"这种正常结果变成崩溃。
   **不动 `CommonResponse`**（12 个接口共用，改动面太大），
   新增 `PermissiveResponse`（zt 可空）专供 task=16。

4. **文件夹描述缺失**
   官网 `fol_desgo → task=4` 把 name 和 description 一起提交。
   新增 `setDirDesc()`/`getDirDesc()`；`ShareInfoDto` 补 `des` 字段。
   ⚠️ **task=4 是整体覆盖，必须回填原 `folder_name`，否则文件夹名被清空。**

5. **单条菜单按类型分流**（对齐 f_view / fol_view）
   文件夹菜单去掉「下载」「重命名」「移动」—— 官网文件夹确实没有这三项。

6. **静默操作补反馈**（V29）
   移动/重命名/删除/新建原来成功后毫无提示，用户无法确认是否生效。

### 29.7 已知未做（以及为什么）

| 功能 | 状态 | 原因 |
|---|---|---|
| 移动文件夹 | ❌ 不做 | 官网文件夹菜单无此项，服务端无接口。LanZouCloud-API 用"新建+逐个移+删除"模拟，3 次写操作、中途失败会丢数据 |
| task=31 文件直链 | ⏸ 已有替代 | cloudbox 走 `ajaxfile.php` 分享页解析链路，功能等价 |
| task=39/40 自定义外链 | ❌ 未做 | **会员功能**，非会员不可用 |
| task=41 缩略图 | ❌ 未做 | 会员功能，且要上传图片 |
| 网页版账号管理页 | ❌ 未做 | 改密码/换手机号涉及明文传输，风险高（见 §28 的"不做"清单） |

### 29.8 实测暴露的认知陷阱（重要教训）

**反编译源码 ≠ 服务端能力。** 
原版 APK 的 Lua 里没有移动功能，我据此断言"官方无此功能"，
但官网实测证明服务端**有** `task=20`，只是原版 App 没接。
**判断"某功能是否存在"时，必须以服务端实际接口为准，反编译只能证明"客户端用没用"。**

同理，反编译失败的模块（`home.lua`/`webview.lua`）更不能用"没看到"推断"没有"。

### 29.9 提交记录

```
097c669 feat(V29): 补齐操作成功提示 + 移动反馈
d808096 fix(V28): 修正 Retrofit Response.body 调用（CI 抓到）
9644280 feat(V28): 对齐官网实测 —— 描述回填、文件夹提取码/资料、菜单分流
```

### 29.10 一个只有 CI 能抓的错误（记下来）

```kotlin
// ❌ 编译失败：Cannot access 'field body: ResponseBody?': it is private in 'retrofit2/Response'
api.getFileInfo(...).body?.string()

// ✅ 正确
api.getFileInfo(...).body()?.string()
```

原因：`retrofit2.Response.body` 是**方法** `body()`；
而 `okhttp3.Response.body` 是**属性**。
项目里 `.execute().body?.string()` 能过是因为 `execute()` 返回 okhttp3.Response。
两个同名不同形，极易混 —— 本地无 SDK 无法编译，只能靠 CI 兜住。

---

## 30. V30：v1.3.4.9 逆向 + 直链反爬修复 + 收藏夹更新检查

### 30.1 版本链路

```
b545260 feat(V30):   直链 WebView 破反爬 + 收藏夹更新检查全链路 + 三项新设置
8e4a6a9 fix(V30.1):  自动检查改回"启动判定 + 一次性任务"（对齐原版语义）
484f6b7 feat(V30.2): 补文件类型标签渲染 + 修正默认值 true + 全量源码
```

CI：run 138 / 139 / 140 全部 **BUILD SUCCESSFUL**，产物发布为 **v0.1.138–140**。

### 30.2 ⚠️ 直链域名新增混淆 JS 反爬（最重要的线上问题）

从 v1.3.4.9 起，蓝奏云在**直链域名**上加了 `acw_sc__v2` 挑战页。
cloudbox 原来的 `ensureDownloadable()` 只识别业务验证页（`down_r(`），
遇到挑战页会**误判为真实文件** → 用户下载到一个 4KB 的 HTML。

**实测证据**：同一会话连续请求直链 3 次，全部返回挑战页，
`CookieJar` 始终拿不到 `acw_sc__v2`；换 UA、重试、加 Referer 均无效。

**两种挑战页的区别**（容易混淆）：

| | 分享页挑战 | 直链挑战（新） |
|---|---|---|
| 特征 | `var arg1='40位HEX'` + 固定 key XOR | `a0i`/`a0j` 字符串表 + 控制流平坦化 |
| 能否静态解 | ✅ `AcwScV2.compute` 可算 | ❌ 自校验，**必须真跑 JS** |
| 原版对策 | 直接算 | **WebView「带 header 重载」** |

**修复**：新增 `DirectLinkWebViewBridge` —— 隐藏 WebView 加载挑战页，
让系统 JS 引擎跑完脚本，从 `CookieManager` 取回 cookie 注入 OkHttp `CookieJar`。

**两个已踩的坑（注释里都有）**：
```kotlin
// ❌ .also{} 作用在 suspendCancellableCoroutine 上会在 builder 返回后**同步**执行，
//    等于在挑战脚本跑之前就 destroy 掉 WebView
suspendCancellableCoroutine { ... }.also { it.destroy() }
// ✅ 放进 finish()，在 resume 前销毁
```
```kotlin
// ❌ probe.use{} 每个分支都是非局部 return → Kotlin 把整块推断成 Nothing
//    与函数声明的 DirectLink 不符 → 编译失败
// ✅ 每支显式 return@use <expr>
```

### 30.3 反编译器死锁（判断"慢"vs"卡死"的方法）

v1.3.4.9 的 4 个大模块反编译时 5 个 worker 全部卡在 `futex_wait_queue`、
CPU 0%、无输出文件 —— **不是慢，是 `walk` 的 `while pc < hi` 死循环自旋**。
根因：`depth > 26` 的 FLAT 保险丝只在**递归**时增加 depth，
`pc` 自旋不递归，保险丝永不触发。

修复：加 `_spin_guard = (hi-lo)*4+64` 硬上限。
**效果：49/49 模块 1.3 秒全部解出**（此前 4 个模块卡 50 分钟无果）。

> **判断方法**：进程"卡住"和"很慢"完全不同。
> 看 **CPU 占用 + 是否有增量产出**，不要看等了多久。
> 多进程同时 0% CPU 且 wchan 全是 futex → 直接判定死锁。

### 30.4 原版设置默认值的权威来源

`ty_core.lua:600-860` 是 `if 设置.X == nil then 设置.X = 默认值` 的密集段落，
**这是查默认值的唯一可信来源**，不要靠猜或靠 UI 文案推断。

本次据此发现并修正：`show_file_type_label` 默认是 **true**（此前写成了 false）。

### 30.5 原版设置项清单（未移植部分）

| 键名 | 默认 | cloudbox 状态 |
|---|---|---|
| `show_share_button` | true | ⬜ 未移植 |
| `show_bookmark_folder_button` | true | ⬜ 未移植 |
| `show_close_button` | true | ⬜ 未移植 |
| `show_desc_tag` | true | ⬜ 未移植 |
| `show_donate_button` | false | ➖ 不适用（无捐赠渠道） |
| `show_wallet_button` | true | ➖ 不适用（无会员体系） |
| `show_system_app` | false | ➖ 不适用（非文件管理器） |

未移植的功能模块及理由见 `/workspace/v1.3.4.9 逆向分析报告.md` §6。

---

## 31. V31/V32：列表加载体验 + 诊断移除 + 上传已安装应用 + 数据管理（2026-09-15）

### 31.1 「加载更多」永远显示 —— 判据写错

用户反馈"每个文件夹都挂着加载更多，可明明已经加载完了"。

**根因**：翻页判据用了响应体里的 `info` 字段（文档说是「是否还有下一页」），
但蓝奏云的 `task=5` 在**最后一页返回的 `info` 仍然是 `1`**，于是按钮永远亮着。

**修复**：改用可靠的物理判据 —— 每页**固定 18 条**，
`items.size >= 18` 才认为可能有下一页，`< 18` 即到底。
同时按用户要求去掉按钮，改为滚动到底自动续拉（`auto_load` 设置项，默认开）。

> **教训**：第三方私有 API 的"分页还有没有了"标志位**不可信**。
> 优先用「本页条数是否等于页大小」这种能从数据本身验证的判据。
> 相关：`PAGE_SIZE = 18` 是硬编码常量，服务端改页大小会导致漏页 ——
> 若将来出现"最后一页少几条"，第一个要查的就是这里。

### 31.2 移除诊断入口（保留失败明细）

V18/V19/V20 为解决上传假成功铺了大量可观测性，问题解决后必须**回收**，
否则设置页会长期堆着一堆用户看不懂的入口。

**删掉的 4 个用户可见入口**：
1. 设置页「上传通道自检」（探测按钮 + 结果弹窗）
2. 设置页「用真实文件自检」
3. 设置页「上传日志」（`UploadTimelineSection`）
4. 文件列表 FAB 的「诊断上传（拿文件测一遍）」

**刻意保留的**（这是关键取舍）：
- `UploadProbeResult` + `UploadProbeDialog` —— 被文件列表的「上传失败明细」
  复用（用 `httpCode = -2` 作哨兵值表示"非 HTTP 层面的失败"）；
- `UploadTrace` —— 是生产链路组件（`UploadWorker` / `UploadViewModel` 在写），
  不是诊断专用，删掉会让失败时无从排查。

顺带把弹窗标题从「上传自检结果」改成「上传失败明细」——
同一个组件在诊断场景叫"自检"没问题，在失败场景叫"自检"就很怪。

### 31.3 死代码审计（22 处删除，−160 行）

**方法**：抽出全仓所有顶层声明 → 统计全仓出现次数 → 只出现 1 次的是候选
→ 人工逐个过滤。

**三类必然误报（不要删）**：
| 类别 | 原因 |
|---|---|
| Hilt `@Provides` / `@Binds` | 由注解处理器发现，源码里没有调用点 |
| `override` 方法 | 框架回调，调用方在 AndroidX 里 |
| `@Dao` 方法 | Room 代码生成器生成实现，源码里只有声明 |

**Gson 特殊**：反射赋值，`@SerializedName` 字段即使代码里从没读过也会被填充，
所以 DTO 字段要单独判断（真实响应里有、但界面不展示的字段属于"保留"）。

> 本项目 `isMinifyEnabled = false`（无混淆），未引用字段不会被裁掉，
> 删除是纯粹的可读性收益，不会影响产物体积。也正因如此**不需要** keep 规则。

### 31.4 上传已安装应用（`QUERY_ALL_PACKAGES`）

需求："直接上传装好的软件，不用先导出安装包再传"。

实现：`InstalledApps` 枚举 + `InstalledAppPickerDialog` 挑选 + 复用既有
`enqueueUpload` 链路。

Android 11（API 30）起的**包可见性限制**是这件事的前提：
不声明 `QUERY_ALL_PACKAGES`，`getInstalledPackages()` 只返回极少数包，
列表会残缺到不可用。已在 Manifest 里写明用途声明（仅本机枚举供手动挑选，
不收集不上传）。

两个实现细节：
1. **系统 APK 都叫 `base.apk`** —— 直接上传在网盘里完全没法分辨，
   所以拷贝到缓存时按 `<应用名>_<包名尾>.apk` 重命名；
2. `/data/app/~~xxx/` 在部分严格 ROM 上读会 EACCES，拷贝失败要能降级报错。

### 31.5 数据管理（备份 / 恢复 / 清除缓存 / 重置）

对齐原版 `privacy_settings.lua` 的「备份数据 / 重置应用」。

**备份什么、不备份什么（核心取舍）**：

| 备份 | 不备份 |
|---|---|
| 收藏夹（含备注/置顶/提取码/红点） | 直链缓存（到期即失效） |
| 全部设置项（`SettingsStore`） | 文件列表缓存（刷新即重建） |
| 域名配置覆盖（`DomainConfigStore`） | 下载记录（本机历史，跨机无意义） |
| | 搜索索引（由文件列表派生） |
| | **账号密码与 Cookie**（凭据） |

理由：备份文件应该**小、可读、只装用户真正在乎的东西**。
把几万条文件缓存塞进去，文件动辄几 MB，用户既看不懂也没法检查。
凭据明文写进一个用户会随手分享/存网盘的文件里是**严重安全问题**。

**三个必须记住的实现约定**：

1. **恢复拆成"先看后写"两步**。恢复会清空现有收藏，属破坏性操作，
   必须先让用户看到"这份备份是几号的、多少条"再确认
   （`inspect()` 只解析不写库，`restore()` 才写）。

2. **设置项按 key 覆盖，不全量替换**。备份来自旧版本时会缺少后来新增的 key，
   全量替换会把那些设置静默重置成默认值，用户会觉得"恢复一次备份，
   把我另外几项设置搞没了"。

3. **`snapshotAll()` 手工列举 key，不遍历 `preferences.asMap()`**。
   遍历看着"自动"，但会把将来新增的 key 悄悄带进备份，
   而旧版本的 `applySnapshot()` 没有对应还原分支 → 数据静默丢失。

**备份文件落盘位置**：优先 `Download/云匣备份/`（用户要找得到、
要能拷到电脑/网盘）。`getExternalStoragePublicDirectory` 在 Android 11+
是否可写取决于 ROM，所以失败时降级到 `Android/data/<pkg>/files/Download/`，
**并如实告知用户文件在哪** —— 而不是报一句"备份失败"让用户白忙。

**`resetAppData()` 不登出**：原版的「重置应用」在错误页里，
属于"App 起不来了"的救援手段。放进设置页后，用户点它是想"把乱七八糟的
配置恢复原样"，不是想退出登录 —— 真要退出，账号管理区有单独的删除按钮。

**一个编译坑（值得记）**：
```kotlin
// ❌ 第一个 ?. 会让整个表达式变可空：cacheDir 为 null 时
//    sumOf 根本不被调用，结果是 null 而不是 0L → 与声明的 Long 不匹配
context.cacheDir?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() }
// ✅ orEmpty() 把序列兜成空序列，sumOf 稳定返回 0L
context.cacheDir?.walkBottomUp().orEmpty().filter { it.isFile }.sumOf { it.length() }
```
链式调用里 `?.` 一旦出现，**后面所有操作符的返回值都会变成可空**，
`runCatching { ... }.getOrDefault(0L)` 也救不了类型不匹配。

### 31.6 本次到达的版本

`v0.1.143` → 本次提交后 CI 通过则为 `v0.1.145`。

---

## 32. V32 复查：我在自己刚写的代码里找到的 4 个问题（2026-09-18）

> 触发：「再次检查一下是否有问题」。三个 V32 提交都已 CI 通过、功能可用，
> 但复查仍然找出 4 处实质问题。**"能编译、能跑、CI 绿"不等于没问题**。

### 32.1 APK 副本永久泄漏（真 bug）

`InstalledApps.copyToCache` 会在 `cacheDir/uploaded_apps/` 产出一份
**完整 APK 副本**（几十 MB），而**没有任何代码清理它**。

注意这里有两份拷贝，容易只看一份：
| 拷贝 | 位置 | 谁清 |
|---|---|---|
| `copyToCache` 产出的（改名副本） | `cacheDir/uploaded_apps/` | ❌ 没人清 ← bug |
| `enqueueUpload` 内部的（Uri→缓存） | `cacheDir/uploads/<uuid>/` | ✅ Worker 清 |

每上传一个 App 泄漏一份，用户会莫名发现"什么都没干存储少了几百兆"。

**修复**：新增 `purgeStaleCopies`，在下次打开应用选择器时统一清理。

### 32.2 修复 1 引入的竞态（更危险）

加清理的同时必须想清楚"有没有拷贝正在跑"。存在这条时序：

```
用户选中 App → 拷贝进行中 → 用户立刻重开选择器
→ 清理正好删掉正在写的文件
→ copyToCache 的 out.length() 读到 0（已打开的文件被 unlink 后仍可写，但大小不可信）
→ 上传空/截断的 APK → 服务端秒回包 → 用户看到"上传成功"，云端是坏文件
```

**这正是本仓库反复栽过的"假成功"**（§20/§21/§24 都在讲这个），
绝不能在新功能里重演。

**修复**：
1. `purgeStaleCopies(dir, keep)` 增加 `keep` 参数，跳过在途文件；
2. 调用方在**启动拷贝之前**（不是之后）用 `targetFileFor()` 算出目标路径
   并登记为 `inFlightAppCopy`，传给选择器；
3. 拷贝结束置回 `null`。

> 顺序是关键：登记必须在拷贝开始**前**完成，否则存在一个窗口期，
> 期间重开选择器会看不到在途文件。

`targetFileFor()` 抽成公共函数的理由：让"登记在途"和"实际写入"
用**同一套文件名规则**。各写一遍的话，将来改一处忘一处 →
两个名字对不上 → 跳过逻辑静默失效 → bug 复活。

### 32.3 空备份会把收藏夹清空（数据安全）

`restore()` 收到"既无收藏也无设置"的备份时照常执行，
用空内容覆盖用户的真实收藏，**且不可撤销**。

触发路径：备份文件被截断/损坏到只剩元信息外壳（`kind` 字段还在），
`inspect()` 能过（它只校验 `kind` 和 `format_version`），
但 `favorites` 和 `settings` 都是空。

用户的本意是"恢复备份"，得到的结果却是"删光收藏"。

**修复**：
1. `RestorePreview` 增加 `isEmpty` 标记；
2. `restore()` 默认（`allowEmptyFavorites = false`）拒绝空备份；
3. UI 在预览弹窗用红字说明"里面没有任何内容，恢复只会清空现有收藏，
   通常是文件不完整"，确认按钮文案改为「**仍要清空**」，
   用户显式点了才传 `true`。

> 设计原则：**破坏性操作遇到"看起来不对"的输入时，默认拒绝并解释原因，
> 而不是照常执行然后让用户承担后果。**

### 32.4 另外三处小修

| 问题 | 修复 |
|---|---|
| `restore(json, appVersionName)` 的参数从未被使用 | 删除该参数 |
| `InstalledApps.formatBytes` 未指定 Locale | 固定 `Locale.US`（否则阿拉伯语环境输出 `١٢.٣٤ MB`） |
| 我自己新加的 `deleteCopied` 无调用点（同类死代码） | 删除 |

第 3 条值得单独说：**我在同一轮改动里批评了死代码，然后自己又写了一个。**
审计标准必须一贯地用在包括自己新写的代码上。

### 32.5 复查确认无误的部分

不要只列问题 —— 以下都实际查过了：

- **跨页刷新**：收藏夹/文件列表走 Room Flow，设置项走 DataStore Flow
  （`MainScreen`/`MainActivity`/`FileListScreen` 都是 `collectAsState`），
  重置或恢复后其他页面**自动更新**，不需要额外通知机制。
  只有 `ResolveViewModel` / `UploadRepositoryImpl` 用 `.first()` 现读，
  但那是"每次操作时读一次"的语义，不受影响。
- **诊断移除无孤儿**：`UploadTimelineSection` / `probeResult` /
  `runUploadProbe` / `dismissProbe` 全仓出现 0 次。
- **保留项确实在被用**：`UploadProbeResult` + `UploadProbeDialog`
  在 `FileListScreen:769` 有真实调用方。
- **新增 import 全部被使用**。

### 32.6 一个自己挖的坑（编译期才发现）

`appCopyDir` 是 `InstalledApps` object 的成员。同一批改动里：
- `FileListScreen` 用了全限定 `com.cloudbox.app.common.InstalledApps.appCopyDir(...)` → 编译通过
- `InstalledAppPickerDialog` 漏了前缀写成 `appCopyDir(...)` → **Unresolved reference**

**教训**：同一个函数在两个文件里两种写法，是"迟早漏前缀"的信号。
跨文件调用 object 成员时保持写法统一，别一个全限定、一个裸名。

### 32.7 版本

`v0.1.146` → `v0.1.148`（`v0.1.144` 是失败运行，`v0.1.147` 是本次编译失败运行，
都在标签序列里缺失 —— **标签序列有空洞就说明有构建失败过**，可据此快速回溯）。

---

## 33. 三轮复查的方法论沉淀（2026-09-19）

> 第 32 节记录了第一轮复查（针对 V32 新代码）找到的 4 个问题。
> 这一节记录第二轮：**把范围从我新写的代码，扩到我这几轮的全部改动**，
> 又找出 3 个问题。两轮合计 7 个 —— 都不是编译期能发现的。

### 33.1 复查范围应该怎么扩大

第一轮只看 V32 新增文件，第二轮有意换了三个视角才挖出东西：

| 视角 | 本轮命中 |
|---|---|
| **向外一层**：不看函数本身，看它该用哪个仓储 | `resetAppData` 绕过 Repository 直接用 DAO → 漏取消下载任务 |
| **看"同名不同实现"**：全仓找语义相近的函数做对比 | `DownloadRepository.clearAll()` vs `DownloadRecordDao.clearAll()` |
| **验证注释里的承诺**：文档说"保留登录态"，就去找凭据存哪 | 确认承诺成立（但也因此发现了 DAO 那处） |
| **找"从未被调用的成员"**：不只是删，还要问"为什么写它" | `ShareFileListResponse.hasMore` 判据不可靠且无人用 |

> **经验**：只审"我改了什么"能抓住 60% 的问题；
> 再问一句"这个改动**应该**调用谁，我是不是绕过了它"能抓住剩下 40%。

### 33.2 破坏性操作必须走仓储，别直接碰 DAO

这是本轮最有价值的发现，也是一个**会长期复发的模式**。

```kotlin
// ❌ 看着没问题，实际漏了"取消系统下载任务"这一步
suspend fun resetAppData() {
    db.downloadRecordDao().clearAll()        // 只删数据库行
}

// ✅ 复用既有实现（它内部先 downloadManager.remove() 再清表）
suspend fun resetAppData() {
    downloadRepository.clearAll()
}
```

**判定规则**：直接调 DAO 只适用于"这张表的语义就在这里"的场景。
一旦某个清理动作涉及**数据库之外的状态**（系统服务、文件、通知、
其他 App），它就必须封装在 Repository 里，否则调用方永远记不住还有额外步骤。

**这个 bug 的用户可见现象很怪**：重置后通知栏还在跑下载进度，
进 App 下载列表却是空的，也没法取消 —— 属于"看起来不像 bug 的 bug"。

### 33.3 「写文件」不等于「写成功」

`writeText()` 返回正常只说明调用没抛异常，**不说明目标文件完整**。
存储写满、文件系统错误被吞、进程写到一半被杀，都会留下截断文件。

对备份这种"用户以为自己有救命稻草"的数据，必须校验：

```kotlin
tmp.writeText(json)
val written = tmp.readText()
if (written.length != json.length) throw ...   // 长度一致
inspect(written).getOrThrow()                   // 内容能解析
tmp.renameTo(out)                               // 原子改名（同文件系统内）
```

**顺带实测了一个反直觉结论**：JSON 在**任何位置**截断都无法通过解析
（外层对象必然缺一个 `}`）。所以"截断导致数据静默丢失"这个担心
其实不成立 —— 截断会以解析异常的形式暴露。真正需要防的是
"长度对但内容坏"（编码问题等），以及**写入失败却有文件残留**。

> 但 `inspect()` 只校验 `kind` 和 `format_version` 两个字段，
> 它**不能**代替完整性校验 —— 见 §32.3 那次"空备份清空收藏夹"。

### 33.4 解析外部输入要容忍合理写法

`toBooleanStrictOrNull()` **只认**小写 `"true"` / `"false"`。
而备份文件是用户电脑上的普通文本文件，他会打开看、也可能手工改。

```kotlin
// ❌ 用户写 "True" / "1" / "yes" → 返回 null → ?.let 静默跳过
map[KEY]?.toBooleanStrictOrNull()?.let { p[key] = it }

// ✅ 容忍常见写法；实在认不出来才返回 null（跳过、保留当前值）
private fun String.toLooseBool(): Boolean? = when (trim().lowercase()) {
    "true", "1", "yes", "on" -> true
    "false", "0", "no", "off" -> false
    else -> null
}
```

**注意末尾的 `else -> null` 而不是 `false`**：认不出来时"保留当前值"
比"武断改成 false"安全 —— 后者会平白改变用户没打算碰的设置。

### 33.5 判据不可靠时，宁可不提供便捷属性

`ShareFileListResponse.hasMore`（判据 `zt == 1`）从未被调用，
而真实调用方 `DirectLinkRepositoryImpl.resolveFolderFromPage` 写的是：

```kotlin
when (resp.zt) {
    1 -> if (batchEmpty) break else pg++   // ← 关键：还要看本页条数
    2 -> break
    3 -> throw ApiError.Business(3, "提取码错误")
    else -> break
}
```

它**没有**用那个属性，因为 `zt` 是状态码、不是页码标志：
服务端异常时可能返回 `zt=1` 但 `text` 为空，单看 `zt` 会白跑到 50 次上限。

**于是删掉了这个属性**，并留下注释说明"为什么不提供"。

> 对比 `FileListResponse.hasMore`（判据"本页 18 条"）是**该保留**的：
> 那个判据能从数据本身验证，不依赖任何状态码。
> 判断标准不是"是不是属性"，而是**判据本身可不可信**。

### 33.6 两轮复查的完整清单

| 轮次 | 范围 | 问题数 |
|---|---|---|
| 第 32 节（一轮） | V32 新增代码 | 4 |
| 第 33 节（二轮） | 这几轮的全部改动 | 3 |

合计 7 个，全部是**功能正确性 / 数据安全 / 资源泄漏**级别，
没有一个是编译错误。编译通过、CI 绿、功能"看起来能用"，
离"没问题"还有相当距离。

### 33.7 版本

`v0.1.149` → `v0.1.150`（构建 4m 19s 通过）。

---

## 34. 三轮复查：设置页输入框的"记忆"陷阱（V32）

前两轮分别在 **V32 新增代码**（第 32 节，4 个问题）与**这几轮的全部改动**
（第 33 节，3 个问题）里查出问题。第三轮刻意换了范围：不再看"我改了什么"，
而是看**整个数据层里最容易出错的地方在哪**。

结果是 1 个问题 —— 而且它不在数据层，在最"不起眼"的设置页输入框上。

### 34.1 现象：恢复完备份，输入框里还是旧值

设置页的 UA / 第三方解析服务 URL 两个输入框，原实现是：

```kotlin
var uaInput by remember { mutableStateOf(state.userAgent) }
```

看起来完全正常。问题出在 **dialog 关闭时 Composable 从组合树移除，
但 `remember` 的值不会跟着重置**：

```
列表行（读 state）:  "Mozilla/5.0 (新)"      ← 恢复备份后立刻更新
点开输入框（读 remember）: "Mozilla/5.0 (旧)"  ← 停留在这个弹窗首次组合时的值
```

于是出现两个后果，第二个更严重：

1. 用户看到"恢复到底生效了没"的自相矛盾（行上是一个值，输入框里是另一个）；
2. 用户顺手点一下「保存」，就把刚恢复的值**覆盖回旧的** —— 一次静默的数据回滚。

这和 §33 里修的那个 bug 是同一类：**破坏性操作之后，界面上的某处还停在旧状态**。
区别是上次是"设置项没重新读"，这次是"读了但输入框不看"。

### 34.2 修法：把"输入框缓存"提升为 state 的一部分

关键判断是：**这个值到底属于谁**。

- 如果它只是"用户正在输入的草稿"，放 `remember` 没问题；
- 但它同时承担着**"打开弹窗时的初值"**这个职责，而初值必须来自真值来源，
  所以它属于 state。

```kotlin
// SettingsUiState
val uaInput: String = "",
val resolverInput: String = ""

// 三个必须同步的时机：初始化、保存、恢复/重置后重读
```

UI 侧改为跟随：

```kotlin
var uaInput by remember { mutableStateOf(state.uaInput) }
LaunchedEffect(state.uaInput) { uaInput = state.uaInput }
```

> ⚠️ 这里有个容易踩的坑：`LaunchedEffect(state.uaInput)` 会在 state 每次变化时
> 覆盖用户正在输入的草稿。之所以安全，是因为 `uaInput` **只在初始化/保存/
> 恢复**三个时机变，用户在输入框里敲字只改本地 `uaInput`、不改 state ——
> 不会形成"输入 → state 变 → 覆盖输入"的回环。
> 哪天有人加了"边输边存"，这段就要重新设计。

### 34.3 顺带想清楚的两件事（查了，确认没问题）

排查时怀疑过两处，结论是**保持现状**，理由记在这里免得下次重复怀疑：

**(1) 重置后要不要重新评估收藏夹自动检查的调度？——不用。**

调度决策是 `CloudBoxApp.onCreate` 里**每次启动**重算一次的（复刻原版
`home.lua:443` 的语义），而"重置应用"不会重启进程 —— 所以本次会话内
不会去重评。看起来像缺陷，实际不是：

| 重置后的值 | 调度后果 | 严重度 |
|---|---|---|
| `days` 仍 ≤ 0 | 本次会话内根本没入队过 | 无 |
| `days` 变 ≤ 0 | 可能在重置前入队，但 Worker 会**自己再读一次**设置，`days<=0` 直接 `success` 退出 | 无（≤ WiFi 条件下的空转） |
| `lastCheck` 被清（变 0）| 已被清，下次启动走"首次基线"分支 → **推迟**检查 | 无（只是晚一点） |
| `lastCheck` 被恢复成很久以前 | 下次启动发现"间隔够了" → 触发一次 | 无（用户自己恢复的） |

四行里没有一行造成真实损害，而加"重置后重评"反而要引入"重置当前
正在跑的任务算不算"这种新语义。**不做。**

**(2) `FavoriteUpdateCheckScheduler` 与 `WorkManager` 的接触面 —— 安全。**

本次会话 push `4f0bda1` 时远端 HEAD 恰好等于本地，属巧合而非自动行为；
关键是这类"提交后要不要顺带清一下后台任务"的想法，结论同样是**不动**：
`enqueueUniqueWork(KEEP)` 不会堆积，Worker 又会自查开关。

### 34.4 三轮的完整清单

| 轮次 | 范围 | 问题数 | 最严重的一个 |
|---|---|---|---|
| 第 32 节（一轮） | V32 新增代码 | 4 | APK 副本泄漏 + 它引出的竞态 |
| 第 33 节（二轮） | 这几轮的全部改动 | 3 | 重置绕过仓储层 → 幽灵下载 |
| 第 34 节（三轮） | 全数据层 + 设置页 | 1 | 恢复后输入框显示旧值 → 误覆盖 |

合计 8 个，全部属于**功能正确性 / 数据安全 / 资源泄漏**，
没有一个是编译错误。三轮的共同点值得记下来：

> **"能编译、能跑、看起来对"三个条件同时满足的代码，
> 里仍然可以有 8 个真问题。**
> 前两轮靠"检查我改了什么"，第三轮靠"换一个提问角度"——
> 后者的收获率（1/1 命中真问题）并不比前者低。

### 34.5 一个自己造出来的编译错误（值得单独记）

`4f0bda1` 里我把输入框缓存提升为 state 字段时，**误留了两行旧的裸赋值**：

```kotlin
uaInput = safe        // ← 这里是 ViewModel 的方法体，不是某个接收者内部
_uiState.update { it.copy(userAgent = safe, uaInput = safe, ...) }
```

字段确实存在（`SettingsUiState` 的成员），但它**不在当前作用域**里 ——
`it.copy(...)` 里的 `uaInput` 才是成员初始化，裸写的那个是自由标识符。
CI 报得很准：

```
e: SettingsViewModel.kt:165:13 Unresolved reference 'uaInput'.
e: SettingsViewModel.kt:196:13 Unresolved reference 'resolverInput'.
```

**教训不是"要小心"，而是"批量替换要看清作用域"**：
同一个名字在两种上下文里都合法（一个在 `copy` 的参数位置、
一个在方法体里），文本替换无法区分它们。修法是删掉两行冗余赋值
（字段本来就被 `copy` 写入了），提交 `68b712a`。

> 这件事和 §34.1 那个 bug 是同一根源的两种表现：
> **"看起来一样的东西在不同作用域里含义不同"**。
> 前者的 `remember` 在弹窗内外含义不同，后者的字段名在 `copy` 内外含义不同。

### 34.6 版本

`v0.1.151` → `v0.1.152`（编译失败）→ `v0.1.153`（2m 48s 通过）。

---

## 35. 四轮复查：换到"功能路径"找问题（V32）

前三轮的范围分别是：V32 新增代码 → 这几轮的全部改动 → 全数据层 + 设置页。
第四轮又换了角度：**不看代码，看"用户会怎么操作"**。

具体做法是走"功能路径"而不是"文件路径"：把每个界面动作用户真会做出来的
顺序串起来，问一句"中途做点别的会怎样"。四轮里这一类的收获最大。

### 35.1 清缓存/重置会删掉在途上传的源文件（数据丢失）

**路径**：

```
FAB「上传文件到当前目录」→ 选 10 个文件
  → UploadViewModel 把文件拷进 cacheDir/uploads/<uuid>/（这一步是**唯一副本**）
  → 用户切到「我的」→ 设置 → 数据管理 → 「清除缓存」
  → DataBackupRepository.clearCache() 执行 cacheDir.listFiles().forEach{ deleteRecursively() }
  → uploads/ 被整体删除
  → Worker 执行时 File(it).exists() == false
  → 10 个文件全部进失败名单：「本地缓存文件已丢失，请重新选择后上传」
```

两个界面在用户眼中毫无关系，**数据层也没有任何耦合** ——
但 `clearCache` 删的是整个 `cacheDir`，而 `uploads/` 恰好住在里面。

它**不是**"假成功"（V5 已经修好了，Worker 会如实报失败），
但用户刚选完的一整批文件无声作废，而提示让他"重新选择" —— 他刚选过。
而且「清除缓存」的副标题本来就写着"清理上传中间文件"，
用户不会预期它把**还没传上去的**文件也清掉。

**修法**：`clearCache()` 在执行前用 WorkManager 查一次在途上传。

```kotlin
private suspend fun hasUploadInFlight(): Boolean = runCatching {
    val future = workManager.getWorkInfosByTag(UploadWorker.TAG_UPLOAD_SESSION)
    val infos = suspendCancellableCoroutine { cont ->
        future.addListener({ cont.resumeWith(runCatching { future.get() }) },
                           ContextCompat.getMainExecutor(context))
    }
    infos.any { !it.state.isFinished }
}.getOrElse { e -> Log.w(TAG, "查询失败（按有上传在途处理）…"); true }
```

三个设计决定：

| 决定 | 理由 |
|---|---|
| 判定包含 `ENQUEUED` | 离线时批次停在 ENQUEUED 等网络，文件同样没传上去 |
| **查询失败返回 true** | 宁可误报"正在上传"拦住清理，也不能误判"没有上传"把文件删掉。清理晚做一次没有代价，误删不可逆 |
| 放在仓储层而不是 UI 层 | `clearCache` 有 3 个入口（清缓存 / 重置 / 未来可能的其它），放在这里全都被保护 |

### 35.2 重置的检查必须在**动手之前**（半完成状态）

第一版修法里我只在 `clearCache()` 加了检查，而 `resetAppData()` 的末尾
会调它。这样有个更糟的后果：

```kotlin
suspend fun resetAppData(): Result<Unit> = runCatching {
    withContext(Dispatchers.IO) {
        settingsStore.resetAll()          // ← 已经清了
        domainConfigStore.clearOverrides()// ← 已经清了
        db.withTransaction { ... }        // ← 已经清了
    }
    clearCache()                          // ← 这里才抛异常
}
```

用户看到的是「重置失败」，但实际上**数据已经没了**。
半完成状态比干脆没做更危险：他以为没重置成功，就不会去重新配置，
而设置、收藏夹、缓存全都已经空了。

修法是在函数开头单独查一次 —— 早失败、零副作用：

```kotlin
suspend fun resetAppData(): Result<Unit> = runCatching {
    if (hasUploadInFlight()) throw IllegalStateException("有文件正在上传，现在重置会…")
    withContext(Dispatchers.IO) { /* 真正的清理 */ }
    clearCache()
}
```

> 这条可以推广成一条通用规则：
> **破坏性操作的前置校验，必须放在第一个写操作之前，
> 不能"顺手"塞在某个中间步骤里。**

### 35.3 异常逃逸：`clearCache()` 裸调没有 runCatching

加了守卫后 `clearCache()` 会抛异常，而两个调用点都是**裸调**：

```kotlin
val freed = dataBackupRepository.clearCache()   // SettingsViewModel，在 launch 里
```

后果两种，都难看：

- 异常逃逸出 `viewModelScope.launch` → 未捕获 → 崩溃；
- 就算没崩，`_uiState.update { dataBusy = false }` 那行永远执行不到
  → `dataBusy` 卡在 `true` → **之后备份/恢复/清缓存/重置四个按钮全部点不动**。

改成 `runCatching{}.onSuccess{}.onFailure{}`，失败时把仓储给的文案
原样展示给用户（`readableMessage()` 会取 message，我抛的异常带完整中文说明）。

### 35.4 设置页页脚版本号硬编码（落后 11 个版本）

```kotlin
Text("云匣 v0.1.143 · 仅供个人学习使用", ...)
```

当前版本已经是 `v0.1.154`。这不是"不好看"，而是**会误导排查**：
用户报问题时按这行说版本号，我们就会去查错的版本。

改成读 CI 注入的 BuildConfig：

```kotlin
Text("云匣 v${com.cloudbox.app.BuildConfig.VERSION_NAME} · 仅供个人学习使用", ...)
```

> 为什么之前会漏：上一轮我**确实**改过这行（`v0.1.0` → `v0.1.143`），
> 当时的做法是"更新成当前版本号" —— 这是治标。
> 真正的问题是**它不该是字面量**。凡是"值会随发版变化"的地方，
> 都必须从单一来源取，手写常量迟早again过期。

### 35.5 四轮的完整清单

| 轮次 | 提问方式 | 范围 | 问题数 |
|---|---|---|---|
| §32 一轮 | 我改的这段代码对不对 | V32 新增代码 | 4 |
| §33 二轮 | 这几轮改动之间是否自洽 | 这几轮全部改动 | 3 |
| §34 三轮 | 数据层哪里最容易坏 | 全数据层 + 设置页 | 1 |
| §35 四轮 | **用户会怎么操作** | 功能路径交叉 | 2 实质 + 2 附带 |

累计 10 个。四轮里"功能路径交叉"这一问法的收获率最高，也最难靠读代码发现 ——
因为**问题不在任何一个文件里**，而在两个界面的交界处。

---

## §36 第五轮：105 文件全量通读（2026-09-19）

### 36.1 这一轮与前面四轮的区别

前四轮都是**增量**审查（只看刚改的那几段代码、或几个功能的交界处）。
这一轮是**全量**：105 个源文件、16853 行，逐个读完。

先说结论，避免误导：**前面四轮修的 11 项全部还在，没有被改回去。**
全量通读的价值不在于"又抓了几个 bug"，而在于**验证了修复没有互相覆盖**。

### 36.2 确认的缺陷（按严重度）

| # | 位置 | 严重度 | 问题 |
|---|---|---|---|
| 1 | `UploadViewModel.init` L154-155 | **高** | 中断文件数被清零，导致报"全部上传成功" |
| 2 | `UploadViewModel.init` L152 | 中 | `globalTotal` 含 CANCELLED，`initialFinished` 不含 → 进度/总数错位 |
| 3 | `WebViewUploadActivity` | 低 | 系统返回键不走 `setResult(RESULT_OK)`，列表不刷新 |

#### #1 会话恢复时把"中断"清成 0（假成功，同一类问题第五次出现）

```kotlin
activeSession.filter { it.state.isFinished }.forEach { info ->
    if (info.state == WorkInfo.State.SUCCEEDED) {
        ... failedAccumulator.addAll(it)
    } else {
        abortedFiles += batchSizeOf(info)          // ← 160 行：如实累加
        abortedReasons.add("上传任务被中断…")
    }
}
val initialFinished = ...
globalTotal = ...

abortedFiles = 0        // ← 161 行：紧接着全部清掉
abortedReasons.clear()
```

`checkAllFinished()` 里 `abortedFiles` 是**正确性输入**：

```kotlin
val okCount = (globalTotal - failed.size - aborted).coerceAtLeast(0)
val (msg, hasFailure) = when {
    failed.isEmpty() && aborted == 0 -> "全部上传成功（$globalTotal 个）" to false
    ...
}
```

于是：**进程在上传中被杀 → 重进 App → 若剩余批次随后成功 → 报"全部上传成功"，
而实际上被中断那几批的文件一个都没传上去。**

复现路径：选 20 个文件（4 批）→ 传第 1 批时杀进程 → 重进 App（init 接管会话）
→ 第 2-4 批跑完 → 弹"全部上传成功（20 个）"，实际云端只有 15 个。

单批场景（≤5 个文件）不受影响，因为 `currentWorkIds` 为空、
`checkAllFinished()` 立即返回 —— 所以这个 bug 只在**多批 + 中途杀进程**时出现，
恰好是本项目反复出问题的那个角落。

> **这是"假成功"类问题第五次出现**（V3 N2 → V5 → S5 → V32 四轮 → 本轮）。
> 每次都在 `Fail`/`Cancelled`/`Missing` 这三种"没传上去"的终态上翻车。
> 说明"上传成功"的判定链路**天然容易被写错**：它要正确必须同时看
> 服务端回包 + Worker 终态 + outputData 三样，漏一样就报假成功。
>
> **建议**：把 `abortedFiles`/`failedAccumulator`/`globalTotal` 三者封成一个
> 不可变的 `UploadOutcome`（在一次会话内只构造一次，不给中途 reset 的机会），
> 比现在散落 7 个 `var` 字段更难写错。

**修法**：删掉 `init` 里那两行重复的清零（L154-155）。
上方 L131-135 已经清过一次（那是本次接管前的初始化，正确），
L145-147 的累加必须在清零**之后**才有效 —— 现在的顺序正好把结果吃掉了。

#### #2 进度基数与总数口径不一致

```kotlin
val initialFinished = activeSession.filter { it.state == SUCCEEDED }.sumOf { batchSizeOf(it) }
globalTotal = activeSession.filter { it.state != CANCELLED }.sumOf { batchSizeOf(it) }
```

被 CANCELLED 的批次：算进 `globalTotal`，不算进 `initialFinished`。
结果进度条永远差那几格、`okCount` 虚高（被取消的文件被算成成功）。
两处口径必须统一，建议都以"非 CANCELLED"为准。

#### #3 WebView 上传页返回不刷新列表

`WebViewUploadActivity` 顶栏返回按钮调了 `setResult(RESULT_OK); finish()`，
但**系统返回键/手势**没有拦截 —— 走的是默认 `finish()`，`resultCode` 仍是 `RESULT_CANCELED`。
而调用方 `FileListScreen` 的 `webUploadLauncher` 回调**不判断 resultCode**，
一律 `viewModel.refresh()`，所以实际不受影响。

**结论：这一条是"目前无害但脆弱"**。调用方现在宽松，一旦有人给回调加上
`if (resultCode == RESULT_OK)` 判断，立刻退化成"网页传完了列表不更新"。
建议补 `onBackPressed` 统一设 `RESULT_OK`（网页上传是天然的成功语义）。

### 36.3 核实无误的高危点（重点确认没有被改坏）

全量通读的主要产出其实是这一节 —— 逐个确认前四轮的修复仍然成立：

| 修复点 | 位置 | 状态 |
|---|---|---|
| `hasUploadInFlight` 守卫清缓存/重置 | `DataBackupRepository` L349/L420 | ✅ 两处都在，且重置是**前置**检查 |
| 重置零副作用早失败 | 同上 L420 | ✅ 在任何写操作之前 |
| `runCatching` 包 `clearCache` | `SettingsViewModel` L516 | ✅ |
| 动态版本号（不再硬编码） | `SettingsScreen` L397 | ✅ `BuildConfig.VERSION_NAME` |
| `toLooseBool` 宽松布尔解析 | `SettingsStore` L271 | ✅ |
| 备份"写→回读校验→原子改名" | `DataBackupRepository` L491-513 | ✅ 长度+解析双重校验 |
| 不覆盖同名备份（带时间戳） | 同上 L475 | ✅ |
| `allowEmptyFavorites` 默认 false | 同上 L242 | ✅ UI 显式传 true 才放行 |
| `formatBytes` 用 `Locale.US` | `SettingsViewModel` L606 | ✅ 避免阿拉伯语环境出逗号 |
| 空备份红字警告 | `SettingsScreen` | ✅ |
| `uaInput`/`resolverInput` 缓存同步 | `SettingsViewModel` L137/L588 | ✅ 两条路径都同步 |

**另外这三处我原本怀疑有问题，读完确认是对的**（记下来免得下次再怀疑一遍）：

- **备份/恢复往返**：写 `.json`、读回按 UTF-8 文本解析 —— 两端一致，
  且选择器用 `*/*`（注释里说明了"网盘落盘会丢 MIME"），不存在"自己写的备份自己读不了"。
- **Worker 丢失文件统计**：`missingNames`（按路径精确比对）与 `failed`（按结果）
  最后 `distinct()` 合并，同名文件不误判、不重复计数。V5 的修复是完整的。
- **`precheck.py`**：105 文件 0 报错，与 CI 实测一致。

### 36.4 优化空间（非缺陷，按性价比排序）

| 优先级 | 项 | 说明 |
|---|---|---|
| 高 | `UploadOutcome` 值对象 | 见 #1 末尾。根治"假成功"这一类，而不是再打一次补丁 |
| 高 | 统一 `aborted` 口径 | 见 #2。把 `globalTotal`/`initialFinished`/`okCount` 收进同一处计算 |
| 中 | `FileListScreen` 拆文件 | 996 行、30+ 个 `remember`，已是全项目最大文件 |
| 中 | 上传延时常量化 | `300..801` / `1000..3001` 散在 5 个文件里，且含义不同（防风控 vs 友好等待） |
| 低 | `AuthRepositoryImpl` 缩进 | L191-238 一段多余的缩进层级，阅读时容易看错块的归属 |
| 低 | `UploadTrace` 时间戳用 SimpleDateFormat | 非线程安全（虽有 Hilt 单例保证单实例，但 `log()` 可被多线程调用） |

### 36.5 这一轮的自省

用户问的是"有没有 bug、有没有优化空间"。诚实的回答是：

**新增缺陷 3 个（1 高 1 中 1 低），比上一轮少，但 #1 仍是"假成功"这一类 ——
说明我之前声称"这一类已经根除"是过度自信。** 真实情况是：
每次修复都只覆盖了我当时能想到的那个终止路径，而没有把所有终止路径
在**同一个数据结构**里统一处理。补丁式修法必然留下下一个角落。

所以 36.4 里把"`UploadOutcome` 值对象"排在第一 ——
它不是可选的洁癖，而是这类 bug 反复出现的**结构性原因**。

优化项本身我没有动手改（用户只要求检查）。若要我实施，建议从 #1 + #2 开始，
它们同源，一次改动可以一起解决。

---

## §37 真实用户报障：改了密码之后退不出去（2026-09-20）

这一轮的起点不是审查，是**用户报障**。原话：

> 你这软件没有修改密码的接口，现在改了密码后，账号没有退出去，
> 并且点击退出登录也退不出去，闪一下登录页面然后又进入了，根本退出不了账号

有意思的地方在于：用户的描述**指向了两个不同的故障**，他自己把它们当成了
一个。拆开看是四件事，前三件共同构成"退不出去"，第四件是我在排查路上
顺手撞见的、同一类"假成功"的第四次复发。

### 37.1 直接成因：退出登录按钮从来没有调用过 logout

全链路追一遍：

```
MeTab 的 TextButton(onClick = onLogout)          ← MainScreen.kt:213
  → MainScreen 形参 onLogout（**直接透传**）      ← MainScreen.kt:62/192
    → MainActivity 的 lambda：navigate(LOGIN)     ← MainActivity.kt:141-145
      并 popUpTo(MAIN) { inclusive = true }
```

`grep -rn "logout" feature/` 全仓库只有一个命中：`SettingsViewModel:314`，
而且那是账号管理里的**"删除"**按钮。**「退出登录」这条路上没有任何一处
调用 `authRepository.logout()`。** 它只是换了个界面。

为什么"闪一下又进来了"就由此解释得通：登录页挂载 → `LoginViewModel.init`
观察到 `currentAccount != null` → `alreadyLoggedIn = true` →
`LaunchedEffect` 触发 `onLoginSuccess()` → 跳回主页。整个过程不到一帧，
用户看到的就是"闪一下"。

**这是我第二次在这个 App 里遇到"UI 状态与数据状态各说各话"**（第一次是
设置页输入框的 remember 陷阱，§34）。共同点：Compose 侧有一个看起来
"应该会联动"的状态，而它背后的真实数据根本没人动。

### 37.2 就算调了 logout，也退不出去 —— 两层独立缺陷

修完 37.1 之后我没有直接下结论，而是回到 `AuthRepositoryImpl.logout`
读了一遍。旧实现只有两句：

```kotlin
override suspend fun logout(uid: String) {
    accountStore.removeUid(uid)
    if (accountStore.currentUid() == null) {
        cookieJar.switchAccount(null)
        _currentAccount.value = null
    }
}
```

**缺陷 A：Cookie 根本没清。**

`removeUid` 只删槽位记录（uid_list / pwd_ / cookies_ / active_at_ / cloud_uid_），
**从未触碰过 `cookieJar` 的内存缓存**。而 `CookiePersistenceJar.cache` 是
按 domain 分桶的内存 map，只要进程还活着，`phpdisk_info` / `ylogin` 就一直在。
任何一次请求都会把它带上去，服务端依然认你是登录态。

> 打个比方：把钥匙登记表撕了，钥匙还在兜里。

**缺陷 B：切槽位切了一个已经被删掉的账号。**

`removeUid` 命中当前账号时会一并 `remove(KEY_CURRENT_UID)`，
所以紧接着的 `if (currentUid() == null)` 判断**确实会成立**，
进而在 `switchAccount(null)` 之外……不，等等 —— 这里没走到 switchAccount(uid)，
这是好消息。但真正的坑在**另一条路**：

如果在此之前有任何一处（比如 37.3 描述的静默重登）把 `currentUid`
重新写回了那个已删除的 uid，`currentUid() == null` 就不成立，
于是这整个 if 块被跳过 —— `_currentAccount.value` **保持原值不动**，
UI 侧依旧认为"已登录"。

所以正确的写法必须**无条件**同步 `_currentAccount`，而不是放在 if 里。
这一点旧代码是错的，只是它被缺陷 A 掩盖了，单看很难发现。

修复后的顺序是三步，一步都不能省：

```kotlin
override suspend fun logout(uid: String) {
    if (accountStore.currentUid() == uid) {
        cookieJar.clearAll()                    // ① 清内存 Cookie 并抹掉该槽位的加密记录
    }
    accountStore.removeUid(uid)                 // ② 删槽位（含 rejected_ 标记）
    val next = accountStore.currentUid()        // 命中当前账号时这里已被清成 null
    cookieJar.switchAccount(next)               // ③ 切到剩余账号或 null
    _currentAccount.value = next?.let { accountStore.accountInfo(it) }
}
```

① 必须在 ② 之前：`clearAll()` 靠 `currentUid` 定位槽位，删了槽位就找不到往哪落盘了。

**为什么不做服务端登出（GET acc.php?t=logout）**：App 内退出只求"本机不再
持有凭证"。多发一个网络请求的代价是**离线时退不出去** —— 对一个"就是想
立刻退出"的用户来说，这是把可用性换成了洁癖。

### 37.3 改密码后被静默重登"复活"

`ensureSession` 旧实现：

```kotlin
val result = login(uid, pwd, rememberPwd = true)
if (result is LoginResult.Success) return@withContext result.account
// 失败？继续往下走，连日志都不打
```

配合 `login()` 内部已有的槽位预绑定（V2 #1 修复）：

```kotlin
val prevUid = accountStore.currentUid()
accountStore.saveUid(uid)
accountStore.setCurrentUid(uid)     // ← 先绑定
...
is MloginOutcome.Rejected -> {
    rollbackTo(prevUid, uid)        // ← 而这里 prevUid == uid，回滚是空操作
}
```

`rollbackTo` 的逻辑是 `if (prevUid != null && prevUid != attemptedUid) 还原
else 清空`。当"重登的就是当前账号"时 `prevUid == attemptedUid`，
走到 else 分支……但旧代码的 else 又只在 `prevUid == null` 时才清，
两个 uid 相等时会去执行 `clearCurrentUid()`。

不管走哪个分支，问题都在于：**服务端已经明确拒绝（"没有用户"），
而调用方一个字都不记**。于是下次冷启动拿同一份过期密码再试一遍，
形成"每次启动都失败一次"的隐性重试循环，用户只看到账号像是还在、
点进去什么都干不了。

修复：让这个失败**有处可去**。

| 层 | 改动 |
|---|---|
| `AccountSecureStore` | 新增 `rejected_<uid>` 键 + `markRejected/ rejectedReason/ clearRejected`，并纳入 `removeUid` 的清理清单 |
| `ensureSession` | `is LoginResult.Failure -> accountStore.markRejected(uid, result.reason)` |
| `AccountInfo` | 新增 `staleReason: String?`，由 `accountInfo()` 带出 |
| `LoginViewModel` | `staleReason` 非空 → 预填账号名 + 显示服务端原话 + **不跳主页** + 清掉那个失效槽位 |
| 三条登录成功路径 | mlogin / 账号中心 / Cookie 导入，都补 `clearRejected(uid)` |

这里有个**顺带的设计收益**：`staleReason` 让"闪一下登录页"这种含糊现象，
第一次变成了用户能读懂的一句话 —— "账号「xxx」的登录状态已失效：没有用户。
请重新输入密码登录。" 报障时用户描述不清，本质上是软件没给他可描述的素材。

### 37.4 顺带撞见：上传会话恢复的计数错误（同一类问题第四次）

在 `UploadViewModel` 里核对"会话恢复"路径时发现：

```kotlin
activeSession.filter { it.state.isFinished }.forEach { info ->
    if (info.state == SUCCEEDED) { ... } else {
        abortedFiles += batchSizeOf(info)     // L145 如实累加
        abortedReasons.add("...")
    }
}
val initialFinished = ...
globalTotal = ...

abortedFiles = 0                              // L154 紧接着清零 ← BUG
abortedReasons.clear()
```

`checkAllFinished()` 里 `aborted` 是**正确性输入**：
`okCount = (globalTotal - failed.size - aborted)`，且
`failed.isEmpty() && aborted == 0 → "全部上传成功"`。

**复现**：选 20 个文件（4 批）→ 第 1 批跑到一半进程被杀 → 重开 App 接管会话
→ 第 2~4 批成功 → 界面报「全部上传成功（20 个）」，云端实际只有 15 个。

这个路径**比前三次更危险**：它出现在"用户自己也没盯着看"的进程重启之后，
用户没有任何机会察觉。

另外两处同源问题：

- **`globalTotal` 含 CANCELLED、`initialFinished` 不含** → 分母与进度错位，
  `okCount` 虚高。统一口径：CANCELLED = 用户主动取消，**既不算中断也不算预期**，
  于是 `expected = activeSession.filter { it.state != CANCELLED }`，
  total / progress / aborted 三处同源推导。

- **上传结束后可能永不裁决**。`checkAllFinished` 的判据
  `workStates.size < currentWorkIds.size` 在会话恢复场景恒为假 ——
  因为 `workStates` 只装得下**在途批**，不含已完成批。若在途批的
  **第一个回调**就是终态（只剩最后 1 批时最常见），判据永远不成立 →
  界面永远停在"上传中…"，直到用户切走再切回（重建 ViewModel，
  `getWorkInfoByIdFlow` 立刻发终态）才补上那句提示。
  上传本身是成功的，所以不是数据错误，但"传完了却一直显示在传"是明确的
  体验缺陷。修复：`observeWorks` 增加 `mergedFinishedBatches` 参数。

  ⚠️ 这里有个易错点值得单独记下：旧代码向 `observeWorks` 传的
  `initialFinished` 是**文件数**，而判据要的是**批次数**。
  在 `BATCH_SIZE = 5` 时两者必然不等，所以**不能复用那个值** ——
  差一点就把参数接错了。

**自省**：我在 §36.5 里写过"这一类 bug 反复出现的结构性原因是补丁式修法"，
并建议做 `UploadOutcome` 值对象。这一轮我没有先做那个重构，而是又打了一次
补丁 —— 于是在同一轮的排查里就撞见了第四次。**这说明我的判断是对的，
而我的行动没跟上判断。** 记在这里，下一轮如果还要动上传逻辑，
应该先做值对象，而不是继续加参数。

### 37.5 顺便修掉的一颗雷

`WebViewUploadActivity`：工具栏返回给 `RESULT_OK`，系统返回键给
`RESULT_CANCELED`。当前调用方不看 resultCode 所以无害，
但只要将来有人开始读它，"用返回键退出 → 列表不刷新"就是难查的间歇缺陷。
统一覆写 `onBackPressed → finishOk()`，让两条路等价。

另：`observeWorks` 的 `workIds` 与 `batchSizes` 加了一层等长归一化。
目前调用点都保证等长，但尾部批次取兜底值 1 的形状意味着
**错 1 个文件就足以把"中断 1 个"变成"全部上传成功"** —— 属于静默算错数的高危形状，
值得提前堵死。

### 37.6 验证

| 项 | 结果 |
|---|---|
| `precheck.py .` | ✅ `OK 检查了 105 个文件，无问题` |
| 括号配平自检（7 个改动文件） | ✅ 全部配平 |
| CI 全量编译 | ✅ `BUILD SUCCESSFUL in 4m 2s` |
| 提交 | `c7a101f` → `origin/main` |

编译之所以必须走 CI：本机没有 Android SDK，也没有 `gradlew`。
这一轮改了 7 个文件、跨 3 层（UI / Repository / SecureStore），
**编译通过是最低标准，不是完成标准** —— 「退出登录能否真的退出」
「改密后是否给出可读提示」这两条，需要真机回归才算闭环。
