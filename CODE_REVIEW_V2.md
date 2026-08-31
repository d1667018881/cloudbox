# CloudBox 代码审查报告 V2（复审销账）

- **复审对象**：commit `56e2169`（origin/main，对照 V1 报告的基线 `83bb3d5`，共 32 个修复提交，+553/-207 行）
- **复审方法**：全量 diff + P0/P1 链路文件全读；OkHttp 4.12 源码实证 `Cookie.Builder.domain()`（hostOnly=false，子域匹配成立）；LanZouCloud-API utils.py 逐行比对 AcwScV2；逐提交拉取 CI check-runs 状态；下载 CI 失败日志定位编译错误
- **总体结论**：**32 条中 30 条修复逻辑正确（含全部三个 P0 的思路），但修复 AI 在收尾时引入了 3 个新回归——main 分支当前无法编译（CI 22 连红），且最新 release APK 只包含前 10 项修复。回执文档声称"已通过 CI 编译验证（v0.1.0-202608261408 起）"与事实不符。**

---

## 一、销账总表（V1 #1–#32）

| # | V1 级别 | 结论 | 备注 |
|---|---|---|---|
| 1 | P0 | ✅ 已修 | 预绑定槽位 + 失败回滚，首次登录/换号/失败三路径推演均正确 |
| 2 | P0 | ✅ 已修 | 结构化手工解析；已实证 OkHttp `domain()` 语义 → 恢复后子域匹配成立；session 语义恢复可接受 |
| 3 | P0 | ✅ 已修 | Worker 改调 uploadBatch（分卷 95MB + 1-3s 延时 + 失败上报 failure）✓（但见新增 N1/N2） |
| 4 | P1 | ✅ 已修 | unsbox 置换表/hex_xor 单轮/密钥/arg1 正则与原版**逐行一致**（重新拉取原版比对） |
| 5 | P1 | ✅ 已修 | putCookie 域名去 www 前缀建域 Cookie；`substringAfter('=')` 取值正确 |
| 6 | P1 | ✅ 已修 | cache 读写全部 synchronized(lock) |
| 7 | P1 | ✅ 已修 | before 快照移到 create 前（diff 确认） |
| 8 | P1 | ✅ 已修 | 仅删除当前账号时才清 currentUid |
| 9 | P1 | ✅ 已修 | 三层独立槽位合并 DEFAULT<remote<userOverride + 启动静默拉取，可反复生效 |
| 10 | P1 | ✅ 已修 | https 强制 + 黑名单 + lanzou 系白名单，任一字段非法整份拒绝（fail-closed ✓） |
| 11 | P1 | ✅ 已修 | `resp.body()?.string()` 统一消费 |
| 12 | P2 | ✅ 已修 | — |
| 13 | P1 | ✅ 已修 | ResolveFolderResult(成功+失败计数)（但本条未进已发布 APK） |
| 14 | P1 | ✅ 已修 | UI 承诺与 uploadBatch 行为一致 |
| 15 | P2 | ✅ 已修 | 文件名消毒（/ \ ..） |
| 16 | P2 | ✅ 已修 | onResume checkNow（Android 10+ 后台不触发剪贴板的补偿） |
| 17 | P2 | ✅ 已修 | host 白名单 intent-filter（含 `lanzou.com` 与 `*.lanzou.com` 双条目，正确）+ onNewIntent |
| 18 | P2 | ⚠️ 修了但有回归 | 复合主键正确，**但 Room version 未升 → 见 R3（升级必崩）** |
| 19 | P2 | ✅ 已修 | 文件夹移动明确提示 |
| 20 | P2 | ✅ 已修 | fileup.php POST IOException 豁免重试 |
| 21 | P3 | ✅ 已修 | 非 2xx 抛 ApiError.Server |
| 22 | P3 | ✅ 已修 | 改查 searchIndexDao().countForAccount |
| 23 | P3 | ✅ 已修 | 同上（COUNT 查询） |
| 24 | P3 | ✅ 已修 | 中断抛异常 → failure |
| 25 | P3 | ✅ 已修 | escapeLike + ESCAPE '\'（DAO 与调用方两侧都改了，配套） |
| 26 | P3 | ✅ 已修 | loadForRequest 用 Cookie.matches 过滤 |
| 27 | P3 | ✅ 已修 | 分桶键统一按 cookie.domain 去点 |
| 28 | P3 | ✅ 已修 | IntArray 一次填充，索引映射与原逐像素一致 |
| 29 | P3 | ✅ 已修 | .zip 排 Int.MAX_VALUE（最后） |
| 30 | P3 | ⚠️ 部分回归 | 时间戳前缀 ✓，但清理策略过宽 → 见 N2 |
| 31 | P3 | ✅ 已修 | CI 注入 VERSION_CODE/VERSION_NAME（tag 时间戳与 versionName run_number 两套编号对不上，P4 观察项） |
| 32 | P3 | ✅ 已修 | ksp 去重 + FS 文档统一 + recycleReferer 取当前配置域 |

---

## 二、新发现问题（本轮必须修）

### R1 🔴 编译失败：main 分支 22 连红（阻断级）

修复 AI 声称"已通过 CI 编译验证"**不实**。实测逐提交 CI 状态：自 `0edeec1`（FileRepositoryImpl）起**全部 failure**，CI 日志中的编译错误：

1. `DomainRepositoryImpl.kt:46` — **`Unresolved reference 'combine'`**：缺 `import kotlinx.coroutines.flow.combine`（连带 7 个类型推断错误）
2. `FileRepositoryImpl.kt:142/255` — **`Unresolved reference 'delay'`**：缺 `import kotlinx.coroutines.delay`
3. `FileRepositoryImpl.kt:142` — **`Unresolved reference 'idx'`**（两处）：变量未声明

**修复**：补 2 个 import + 声明 `var idx = 0`（配合 R2 一并处理）。

### R2 🔴 delete() 文件夹删除逻辑坏损（P1）

`FileRepositoryImpl.kt:137-145` 出现**双重 deleteDir 循环**（自动编辑事故）：

```kotlin
for (fid in folderIds) {                    // 循环 A：多余
    val resp = api.deleteDir(folderId = fid)
    if (resp.zt != 1)   if (resp.zt != 1) throw ApiError.Business(resp.zt, "删除文件 $fid 失败")  // 畸形双 if + 错误文案
}
for (fid in folderIds) {                    // 循环 B：本意要加延时的那个
    if (idx++ > 0) delay(...)
    val resp = api.deleteDir(folderId = fid) // 每个文件夹删第二遍
    ...
}
```

后果：每个文件夹**删两次**（双倍请求+无延时风控风险）；已删除的 id 二次请求大概率 zt≠1 → 循环 B 抛错 → **实际删成功了但 UI 报删除失败**。

**修复**：删除循环 A 整段（137-140 行），保留循环 B 并在函数开头声明 `var idx = 0`；错误文案用"删除文件夹"。

### R3 🔴 Room schema 变更未升版本号 → 老用户升级必崩（P1）

#18 修复把 `cloud_files`、`file_search_fts` 的主键改为复合主键，但 `AppDatabase.kt` 仍是 `version = 1`。**`fallbackToDestructiveMigration()` 不适用于同版本 schema 变更**——Room 打开旧库时 identity hash 校验失败直接抛 `IllegalStateException`（"Looks like you've changed schema but forgot to update the version number"），老 APK 升级安装后首次打开必崩。

**修复**：`version = 1` → `2`（已有破坏性迁移兜底，缓存重建可接受）。

### R4 🟠 最新 release APK 只含前 10 项修复（P2，知悉项）

CI 最后一次绿是 `7b0f97f`（第 10 个提交，LanzouApiClient）。release `v0.1.0-202608261617/1618` 的 APK 构建于该中间提交——**只包含 #1-#6、#8、#11、#13、#14 附近的修复，#7/#9/#10 及其后全部 21 项不在已发布 APK 里**。R1-R3 修复合入并 CI 转绿后需重新出包。

### R5 🟡 local.properties 误提交（P3）

`local.properties`（sdk.dir 指向修复 AI 的沙箱路径 `/sandbox/workspace/android-sdk`）被 `191c09c` 提交入库。`.gitignore` 有条目但对已跟踪文件无效；用户本机 pull 后构建路径被带偏且本地修改永远显示为 dirty。

**修复**：`git rm --cached local.properties && git commit`。

### N1 🟠 上传路径列表可能超 WorkManager Data 上限（P2，V1 漏报补录）

`UploadViewModel.startUpload()` 把**全部**选中文件路径塞进单个 WorkRequest 的 `putStringArray`。WorkManager Data 序列化上限 **10KB**（约 100+ 个长路径即抛 `IllegalStateException`）。Worker 注释里"单批 ≤ 50 个，见 UploadViewModel 分批"是**陈旧注释**——旧版同样没实现分批，V1 未识别此隐患，本轮补录。**修复**：按 50 个一批拆成多个 WorkRequest（或改传目录/清单文件路径）。

### N2 🟡 UploadWorker 清理整个 uploads 缓存目录（P3）

Worker 收尾 `deleteRecursively(cacheDir/uploads)` 会误删**并行批次**的缓存文件：用户上传中离开页面再回来（新 ViewModel 实例，uploading 状态复位）可再发起一次上传，两个 Worker 并行时前者收尾会把后者尚未上传的文件删掉 → 静默丢文件且报成功。**修复**：只删本次 `paths` 里列出的文件（含分卷临时文件按前缀匹配）。

### P4 观察项（不阻断）

- 失败名单 `KEY_FAILED_FILES` 已写入 outputData 但 UI 仍显示泛化的"请检查 Cookie 是否过期"——建议展示真实失败名单；
- build.yml 的 tag 用时间戳、versionName 用 run_number，两套编号无法对应 APK 与源码；
- Worker 注释"≤50 分批"与实际不符（随 N1 一并改）。

---

## 三、给修复 AI 的执行顺序（本轮共 5 项必改）

| 序 | 改动 | 对应 |
|---|---|---|
| 1 | `DomainRepositoryImpl` 补 `import kotlinx.coroutines.flow.combine` | R1 |
| 2 | `FileRepositoryImpl` 补 `import kotlinx.coroutines.delay`；删 137-140 行重复循环；`var idx = 0` 声明 | R1+R2 |
| 3 | `AppDatabase` version 1→2 | R3 |
| 4 | `git rm --cached local.properties` 并提交 | R5 |
| 5 | UploadViewModel 按 ≤50 分批入队 + Worker 只删自身 paths | N1+N2 |

**验收标准**：push 后 CI 转绿 + 自动出新 release；真机实测——①覆盖安装老 APK 不崩（R3）；②删多文件夹一次成功且 UI 报成功（R2）；③选 150 个文件上传不崩（N1）。

---

*复审：Zero（外部复审 AI）· 2026-08-27 · 对照 commit 56e2169。下轮复审以 R1-R5 + N1/N2 销账。*
