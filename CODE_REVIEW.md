# CloudBox 代码审查报告（V1 首轮全读）

- **审查范围**：commit `83bb3d5`（main，全部 80 个 Kotlin 文件 + 构建配置 + CI，共 7184 行）
- **审查方法**：全量通读源码；关键结论均已实证验证——OkHttp 4.12.0 源码（Cookie.toString / BridgeInterceptor 行为）、LanZouCloud-API 官方 utils.py（acw_sc__v2 原版算法）逐一比对
- **总体结论**：架构分层清晰、注释质量高、域名拦截器/风控延时等设计意图好，但**认证 Cookie 管理链路存在两个 P0 级级联 bug，账号密码登录在首次登录场景基本不可用**；上传 Worker 与 UI 承诺脱节；acw_sc__v2 移植有误。**建议先修 #1~#3 再进行任何功能迭代**。

**分级统计**：P0 × 3｜P1 × 7｜P2 × 10｜P3 × 12（P4 若干条并入 P3）

---

## P0 —— 核心功能失效（必修）

### #1 账号密码登录后 Cookie 必然丢失（首次登录 / 换号登录必现）

**位置**：`AuthRepositoryImpl.login()` + `CookiePersistenceJar.persist()/switchAccount()`

**时序缺陷**：
1. `apiService.login()` 执行期间，`saveFromResponse()` 把 phpdisk_info 写入**内存 cache**，并调 `persist()`；
2. `persist()` 里 `currentUid ?: return`——首次登录时 currentUid 为 **null**，**直接跳过落盘**；若当前已登录账号 A 再登录账号 B，则 B 的凭证**整体覆盖写入 A 的槽位**（数据污染）；
3. `login()` 判定 `isLoggedIn()`==true（内存里有）→ `switchAccount(uid)` → **cache.clear() 后从磁盘加载** → 磁盘上没有新账号的 Cookie → **空**；
4. 返回 Success，但此后所有请求无 phpdisk_info → 必然失败，表现为"登录成功却提示 Cookie 过期"。

唯一能正常工作的路径：重登录**当前已绑定槽位的同一账号**（如 ensureSession 静默重登）。

**修复建议**：`login()` 发请求**之前**先 `accountStore.saveUid(uid) + setCurrentUid(uid) + cookieJar.switchAccount(uid)`——先把槽位绑定到目标账号，让响应期 `persist()` 落到正确槽位；登录失败再回滚槽位。（或给 `switchAccount` 加"迁移当前 cache 到新 uid"模式。）

### #2 Cookie 持久化格式被属性串污染 → 重启后凭证失效

**位置**：`CookiePersistenceJar.persist()` / `parseCookieLine()`

**实证**：OkHttp 4.12.0 `Cookie.toString()` 输出**小写**属性：
`phpdisk_info=xxx; expires=Sat, 12 Sep 2026 ...; domain=.woozooo.com; path=/`

而 `parseCookieLine()` 用 `line.contains("Domain=")`（**大写 D**）判断——**永不相等**，全部落入纯键值对分支：
`value = line.substring(eq + 1).trim()` → value 变成 `xxx; expires=...; domain=.woozooo.com; path=/` 整串垃圾。

**后果**：每次 App 重启 / 切换账号再切回，恢复出的 phpdisk_info 值带垃圾后缀 → 服务端判凭证无效 → 又要重新登录。与 #1 叠加：**登录态基本无法跨进程存活**。

**修复建议**：
- 持久化改为结构化存储（name / value / domain 三字段），或
- 短平快修法：`contains("domain=", ignoreCase = true)` + 取 value 时按第一个 `;` 截断：`line.substring(eq+1, line.indexOf(';').takeIf{it>eq} ?: line.length).trim()`。

**附带的第二个地雷**：即使修好大小写，Domain 分支用固定 URL `https://www.lanzou.com/` 去 `Cookie.parse` woozooo 域的 cookie，会因 domain 不匹配返回 **null**（OkHttp 行为）——正确做法是按 cookie 自身 domain 构造解析 URL。

### #3 上传 Worker 三连缺陷：不分卷、无延时、失败误报成功

**位置**：`UploadWorker.doWork()` + `UploadViewModel.checkOversize()/startUpload()`

1. UI 明确承诺"存在超过 100MB 的文件，将自动分卷（95MB/卷）上传"（`oversizeHint`），但 Worker 逐文件调 `uploadFile()`——**没有分卷逻辑**（分卷在 `uploadBatch()` 里，Worker 未复用）→ 超限文件必然被服务端拒绝；
2. Worker 循环**没有 1-3s 随机延时**（`uploadBatch` 有），后台批量上传直接绕过防风控要求；
3. `uploadFile()` 的返回值被完全忽略，**无论成败都 `Result.success()`** → 全部失败也提示"上传完成"。

**修复建议**：Worker 循环体改为调用 `uploadBatch()`（或补齐分卷 + `delay` + 失败计数，存在失败时 `Result.failure()` 并把失败文件名塞 outputData）。

---

## P1 —— 功能缺陷 / 安全

### #4 AcwScV2 算法移植错误（对照原版逐行验证）

**位置**：`AcwScV2.kt`

与 LanZouCloud-API `utils.py` 原版的三处偏差：
1. **缺 `unsbox()` 位置置换**：原版先用 `v1=[15,35,29,24,33,16,1,38,...]` 40 位重排 arg1——此处完全没有；
2. **轮数错**：原版单轮 XOR，此处迭代 2 轮；
3. **密钥取值错**：原版 `int(key[idx:idx+2],16)`（两位 hex → 0-255 字节级），此处 `key[i/2].digitToInt(16)`（单字符 → 0-15）。

**后果**：compute() 必然返回错误 cookie → 带 acw 挑战的分享页直链解析 100% 失败（静默降级为"无法提取 sign"，用户无从知道原因）。注释声称"源码 calc_acw_sc__v2 实现"，实际是重写走样。

**修复建议**：照原版补 unsbox + 单轮字节级 XOR；arg1 正则同时兼容 `[0-9A-Z]+` 与 `\x` 转义形态（现有兼容逻辑可保留）。

### #5 acw cookie 会被 CookieJar 覆盖（重试必然失败）

**位置**：`DirectLinkRepositoryImpl.getPage()`

`builder.header("Cookie", computed)` 手动携带计算出的 acw_sc__v2。但 OkHttp `BridgeInterceptor` 在 cookieJar 返回非空时会用 `header()` **整体替换** Cookie 头（已查源码确认）。首次访问分享页时服务器通常会 Set-Cookie（如 Aliyun WAF 的 `acw_tc`，host 含 "lanzou" 会被 saveFromResponse 存进 jar）→ 重试请求的手动 Cookie 被冲掉 → 挑战重试失败。

**修复建议**：把算出的 acw_sc__v2 写入 `CookiePersistenceJar`（新增按 host upsert 的方法），让 jar 统一管理；或 getPage 改用无 cookieJar 的独立 client 手动合并两种 cookie。

### #6 CookiePersistenceJar 线程不安全

**位置**：`CookiePersistenceJar` 全类

`cache` 是普通 `HashMap<String, MutableList<Cookie>>`：`saveFromResponse/loadForRequest` 由 OkHttp 线程池**并发**调用，`switchAccount/persist/clearAll` 来自协程/主线程，无任何同步 → 数据竞争，可能丢 Cookie 或抛 `ConcurrentModificationException`（crash）。`currentUid` 加了 `@Volatile` 只解决了可见性一半。

**修复建议**：所有 cache 读写用 `synchronized(lock)` 包裹（Cookie 量小，锁竞争可忽略），或 `ConcurrentHashMap` + 同步块。

### #7 createFolder 的前后对比策略失效（顺序写反）

**位置**：`FileRepositoryImpl.createFolder()`

`before` 快照在 `api.createFolder()` **之后**才取——两次 getAllFolders 结果相同，diff 恒空 → 新文件夹 id 永远返回 null。LanZouCloud-API 原版是"先取 before → create → 再取 after"。

**影响**：新建文件夹后返回值拿不到 id（UI 无法立即进入新目录，只能靠整页刷新兜底）。

**修复建议**：把 `before` 移到 createFolder 调用之前，一行挪动即可。

### #8 removeUid 无条件清除 currentUid（删别人账号会踢掉自己）

**位置**：`AccountSecureStore.removeUid()`

`.remove(KEY_CURRENT_UID)` 无条件执行——删除**非当前**账号 B 时，当前账号 A 的 currentUid 也被清掉 → `AuthRepositoryImpl.logout()` 里 `currentUid() == null` 成立 → `switchAccount(null)` → **正在使用的账号 A 在 UI 上被登出**。

**修复建议**：`if (currentUid() == uid) remove(KEY_CURRENT_UID)`，仅在被删账号是当前账号时清除。

### #9 远程域名配置只生效一次（热更新机制自废武功）

**位置**：`DomainRepositoryImpl.fetchAndApplyRemote()`

`merge(remote, overrides)` 后把**合并结果整体写回 overrides 槽位**（`saveOverrides(merged)`）。第二次远程更新时，旧远程值已变成"最高优先级的本地覆盖"，**新远程值永远被压住**。对一个以"域名漂移可远程热修"为设计目标（AI_MAINTENANCE §0/§7.4）的机制，这是致命的。

**附带发现**：全工程**没有任何启动时自动拉取远程配置的代码**（`fetchAndApplyRemote` 仅由设置页手动触发），文档"启动时拉取一次"与实现不符。

**修复建议**：overrides 只存用户手改字段；远程配置存独立 key（remote_*），合并顺序 DEFAULT < remote < userOverride；启动时（CloudBoxApp onCreate）用已保存的 remoteUrl 拉一次。

### #10 远程域名配置未做合法性校验（凭证窃取向量）

**位置**：`RemoteDomainSource.fetch()`

只对 `fallbackDomains` 做了黑名单过滤；`loginEntry/diskMain/shareBase/uploadServer` 四个主字段**原样接受**——无 https 强制、无域名白名单校验。被篡改的 Gist 可把全部 API 流量（**连同 phpdisk_info Cookie**）导向任意服务器 = 账号凭证窃取。`DomainUtils.isForbidden` 注释声称"任何来源（含远程配置）都不得使用"，实现没做到。

**修复建议**：fetch 成功后对四个主字段做 `isForbidden + 白名单（*.woozooo.com / lanzou 系域名）+ scheme==https` 校验，任一不通过整份拒绝并报错（不要静默部分应用）。

---

## P2 —— 健壮性 / 一致性

### #11 login() 成功路径 Response body 未消费（连接泄漏）

**位置**：`AuthRepositoryImpl.login()`
失败分支有 `resp.body()?.string()`，成功分支直接丢弃 → OkHttp 连接不能复用，泄漏连接池槽位（多次登录后连接池劣化）。
**修复**：函数末尾统一 `resp.body()?.close()`（或 `resp.raw().close()`）。

### #12 批量删除/移动/回收站操作缺防风控延时

**位置**：`FileRepositoryImpl.delete()/moveFiles()/recycleAction()`
循环逐项请求，无 1-3s 随机延时。需求规格与注释反复强调"所有批量操作（解析/上传/删除）1-3s"，直链与上传都做了，唯独这里漏了。回收站每项还是 GET+POST 两次请求，暴露面更大。
**修复**：循环内加与 `resolveBatch` 相同的 `delay(ThreadLocalRandom.nextLong(1000, 3001))`。

### #13 resolveFolder 静默丢弃失败项

**位置**：`DirectLinkRepositoryImpl.resolveFolder()` 最后 `mapNotNull { it.getOrNull() }`
文件夹里有 20 个文件、解析失败 5 个时，用户只看到 15 个，无任何提示。
**修复**：改为 `map { it }` 保留 Result，UI 展示"成功 N / 失败 M（可重试）"。

### #14 UploadViewModel 一次传全部路径，超 10KB 崩溃

**位置**：`UploadViewModel.startUpload()` → `Data.Builder.putStringArray`
WorkManager `Data` 序列化上限 10240 字节；cache 路径约 60-80 字节/条，**约 120-170 个文件即抛 IllegalStateException**（未捕获 → crash）。
**修复**：分批 enqueue 多个 Worker；或只传任务 id，路径落库/落临时文件。

### #15 下载文件名未净化（服务端可控）

**位置**：`DownloadRepositoryImpl.enqueue()` → `setDestinationInExternalPublicDir(DIRECTORY_DOWNLOADS, fileName)`
fileName 来自服务端返回的 `inf` 字段。含 `/`、`..` 时可能写出 Downloads 目录（路径穿越）或抛 IllegalArgumentException；同名静默覆盖已有文件。
**修复**：`fileName.replace("/", "_")` + 规范化校验 + 冲突时自动追加 `(1)`。

### #16 剪贴板监听在 Android 10+ 基本失效

**位置**：`ClipboardLinkWatcher` + `MainActivity`
Android 10+ 的 `OnPrimaryClipChangedListener` **只在应用持有前台焦点时回调**。用户在其他 App 复制链接再切回 CloudBox（最典型场景）时无事件触发，而 MainActivity 也没有在 onResume 主动 check 一次 → 功能在最常见路径上无效。
**修复**：`LifecycleON_RESUME` 时调一次 `checkClipboard()`（前台读剪贴板合法）。

### #17 VIEW intent-filter 无 host 过滤且 intent 数据被丢弃

**位置**：`AndroidManifest.xml` MainActivity + MainActivity 无 onNewIntent 处理
`<data android:scheme="https"/> <data android:scheme="http"/>` 未限定 host → **App 成为全系统所有网页链接的候选打开方式**（每次点链接都弹选择框，打扰极强）；且即便被选中打开，MainActivity 完全不读 intent.data，链接被静默丢弃。
**修复**：给 intent-filter 加 `android:host` 过滤（lanzou 系域名），并在 MainActivity `onCreate/onNewIntent` 解析 `intent.data` 跳转解析页；若不想支持外链打开，直接删掉整个 filter。

### #18 FileCacheEntity 主键设计错误 → REPLACE 永不生效、翻页重复堆积

**位置**：`FileCacheEntity`（PK=autoGenerate rowId）+ `FileRepositoryImpl.getPage()`
`OnConflictStrategy.REPLACE` 在自增主键下永不触发（每次都是新行）；且 task=47 目录列表**每页都重新拉取重新插入** → 每翻一页，该目录下所有子文件夹在缓存表里多一份。UI 列表直读接口返回值所以不显重复（万幸），但搜索数据源膨胀、`allFiles` 全量加载变慢。
**修复**：主键改 `(accountUid, id)` 复合主键，REPLACE 即生效；或每页 insert 前先 `DELETE WHERE accountUid AND parentId`（目录部分）。

### #19 批量移动静默忽略文件夹

**位置**：`FileListViewModel.moveSelected()` → `fileRepository.moveFiles(fileIds)`
选中的条目里文件夹被过滤掉只传文件——文件夹原地不动，但 UI 退出多选、提示成功，用户以为移动完成。
**修复**：`moveFiles` 增加 folderIds 参数（doupload task=61 一样支持文件夹），或 UI 移动场景禁选文件夹并提示。

### #20 RetryInterceptor 对非幂等 POST 盲目重试

**位置**：`RetryInterceptor.intercept()`
IOException 后无差别重试整个请求：上传（fileup.php）中途断流重试 → **服务端可能已收到 → 重复上传**；建夹/删除同理。429/5xx 重试没问题。
**修复**：对 POST 且路径属 fileup.php / createFolder 类的请求，IOException 时不自动重试（抛给上层决策），或至少上传请求豁免。

---

## P3 —— 工程细节 / 性能 / 死代码

### #21 getPage() 忽略 HTTP 状态码
404/403/429 的错误页 HTML 直接进正则 → 报"无法提取 sign"误导排障。建议 `resp.use { if (!it.isSuccessful) throw ApiError(...) }`。同理 `resolveViaThirdParty` 也不看状态码。

### #22 isSynced 判定查错表
注释说"存在索引数据即认为同步过"，实现查 `fileCacheDao().allFiles()`（浏览任意目录即非空）→ **永远显示已同步**。应查 `searchIndexDao` 行数。

### #23 allFiles() 全量加载只为判空
`isSynced` 里整表拉出来 `isNotEmpty()`。换 `SELECT EXISTS/COUNT`（DB 层加个 `hasAny(uid): Boolean` 查询）。

### #24 syncAll 中途失败静默截断
`getOrNull() ?: break` —— 网络错误时部分索引进库但 syncAll 返回成功。建议失败计数写入返回值，UI 提示"部分同步失败"。

### #25 LIKE 查询未转义 `%`/`_`
`searchIndex` 与 `searchLike` 两处：搜"50%折扣"这类文件名行为异常。对 keyword 做 `[\\%_]` 转义。

### #26 loadForRequest 不过滤过期 Cookie + 手工解析固定 20 天有效期
真实过期（服务器下发较短有效期）的 cookie 仍会被发送。建议恢复/查询时用 `cookie.matches(url)` 过滤（同时解决 #2 的一部分语义）。

### #27 Cookie 分桶键不一致
保存按**请求 host** 分桶、恢复按 **cookie.domain** 分桶 → 同一 cookie 重启前后匹配行为不同（跨子域场景漂移）。统一按 cookie.domain 分桶（hostOnly cookie 的 domain==host，天然兼容）。

### #28 QrCodeUtil 逐像素 setPixel
512×512 = 26 万次 JNI 调用。改 `Bitmap.createBitmap(IntArray, ...)` 一次填充，快一个数量级。

### #29 死代码四件 + 一个潜伏的合并炸弹
- `FileCacheDao.observeFolder/getFolder`：无调用方——README 宣称的"缓存秒开+离线可看"实际没接线；
- `LanzouApiService.downProcess`：无调用方（实际走 raw OkHttp）；
- `SplitZipUtil.mergeVolumes/isVolume`：无调用方，且 **volumeOrder 把 .zip 排在第一位是错的**（zip4j 分卷标准顺序 .z01…zNN → .zip，.zip 是含中央目录的**最后**一段）——一旦未来接线就是数据损坏级 bug，建议现在就修正排序并留注释；
- `SettingsStore.thirdPartyResolverUrl` 相关 fallback 逻辑与死示例 Gist（`DEFAULT_REMOTE_DOMAIN_URL` 指向不存在的 example gist，拉不到时静默回退——行为可接受但文档应说明）。

### #30 copyUriToCache 缺陷二连
同显示名文件互相覆盖（`cache/uploads/<displayName>` 直接写）；上传完成后不清理缓存目录 → cacheDir 无限膨胀（系统低空间才清）。建议加时间戳/UUID 后缀 + Worker 成功后删除。

### #31 版本号永不递增
versionCode 恒 1、versionName 恒 0.1.0，CI tag 带时间戳但 APK 版本不变——无法区分构建，依赖"同签名同 versionCode 可覆盖安装"的侥幸行为。建议 CI 注入 `versionCode = $(date +%s) % 1000000`。

### #32 工程杂项
- `build.gradle.kts`：`ksp(libs.hilt.compiler)` 声明了两次（118/121 行）；
- keystore + 密码入库：私有仓库自用可接受（评估后不修，记录风险）；若仓库转公开必须先移除并换签名（注意换签名=老用户无法覆盖安装）；
- `SearchIndexEntity` 文档与表名自称 FTS4，实际普通表 + LIKE（DAO 注释说了实话）——统一说法，避免后续维护者真去用 MATCH 查询（会直接抛 SQLiteException）；
- 回收站操作 Referer 硬编码 `https://pc.woozooo.com/mydisk.php`，域名漂移/用户改配置后不一致——建议取 `domainInterceptor.snapshot().diskMain`。

---

## 修复优先级建议（给修复 AI 的执行顺序）

| 批次 | 条目 | 理由 |
|---|---|---|
| 第一批 | #1 #2 #6 | 认证链路级联崩溃，其他一切功能的前提 |
| 第二批 | #3 #4 #5 | 上传承诺兑现 + 直链解析两条主干 |
| 第三批 | #7 #8 #9 #10 | 单点功能缺陷 + 安全兜底 |
| 第四批 | P2 全部（#11-#20） | 健壮性收敛 |
| 第五批 | P3 按顺手程度 | 工程质量 |

**验证提示**：#1/#2 修复后请实测三条路径——①冷启动首次账号密码登录 → 任意拉文件列表；②登录后杀进程重启 → 直接拉列表（不再要求重新登录）；③账号 A 登录 → 切账号 B 登录 → A 的 Cookie 仍完好。#4 修复后用带 acw 挑战的分享页实测解析。

---

*审查：Zero（外部复审 AI）· 2026-08-26 · 对照 commit 83bb3d5。下轮复审请以本报告编号销账，处理结果回写 AI_MAINTENANCE.md。*
