# CloudBox 代码审查报告 V3（第三轮复审）

- **复审对象**：commit `b41a9fe`（origin/main，对照 V2 基线 `56e2169`，共 38 个提交，+466/-134 行）
- **复审方法**：全量 diff + 改动文件全读；check-runs API 逐提交核验 CI；GitHub Secrets API 确认 KEYSTORE_PASSWORD 已配置；WorkManager 链式语义对照官方文档推演
- **总体结论**：**V2 全部 7 项（R1-R5 + N1/N2）修复正确销账，CI 在 HEAD 转绿，release v0.1.88 与 HEAD 一一对应，安全加固扎实**。但本轮重构上传链路时引入 **2 个 P1 回归**：防风控延时丢失（V1 #3 回归）+ 链式调度失败语义冲突（一批失败后续静默不传）。外加 2 个 P2 和若干 P3。

---

## 一、V2 销账总表（7/7 通过）

| # | V2 条目 | 结论 | 核验方式 |
|---|---|---|---|
| R1 | 编译失败（缺 import/idx 未声明） | ✅ 已修 | combine/delay import 补齐，idx 声明，CI HEAD success |
| R2 | delete 双循环（文件夹删两遍误报失败） | ✅ 已修 | 重复循环删除，且顺带给批量删除补了 1-3s 延时（超额完成） |
| R3 | Room version 未升 | ✅ 已修 | version 1→4（含 isFolder 字段变更），destructive migration 兜底 |
| R4 | keystore 密码入库 | ✅ 已修 | 密码改走 `KEYSTORE_PASSWORD` 环境变量 + GitHub Secrets；本地构建 "change-me" 兜底合理 |
| R5 | local.properties 误提交 | ✅ 已修 | 已删除 |
| N1(V2) | WorkManager Data 10KB 上限 | ✅ 已修 | chunked(50) 分批 + 链式串联（但见本轮 N2 新问题） |
| N2(V2) | Worker 清理整个缓存目录 | ✅ 已修 | 只删本次 paths + 分卷临时目录前缀（但见本轮 N3 新问题） |

**加分项**：修复 AI 在 AI_MAINTENANCE.md §12 里明确写下了"必须验证最后一个 commit 的 CI run"的教训——V2 抓到的谎报 CI 问题被制度化吸收了。tag = versionName = run_number 三者统一后，APK ↔ 源码可追溯（v0.1.88 = HEAD b41a9fe，已核）。

**新功能质量**（下载暂停恢复/上传失败汇总/文件夹搜索/通知权限/安全加固）：方向和实现总体扎实——
- 域名白名单统一收敛到 `AppConstants.TRUSTED_SHARE_HOSTS`（链接识别 + Cookie 域过滤 + 远程配置三处共用）✓
- 暂停/恢复是诚实的"重下"语义，注释明确说明非断点续传 ✓；`resume()` 直接复用 buildRequest 更新记录 id，无主键冲突 ✓
- LoginViewModel 改 collect 修复静默重登后不跳转 ✓
- 备份规则排除数据库与上传临时目录 ✓
- Android 13 通知权限运行时申请 ✓

---

## 二、本轮新问题

### N1（P1）普通文件连续上传无防风控延时——V1 #3 部分回归

**位置**：`UploadWorker.doWork()` 循环体

**现状**：Worker 逐文件直调 `uploadRepository.uploadFile()`，循环体内**没有任何延时**。分卷上传（uploadSplit 卷间）和批量删除（本轮刚加的）都有 1-3s 随机延时，唯独最高频的"普通文件批量上传"没有——选 50 个小文件会背靠背连发 50 个请求。

**为什么重要**：V1 #3 的修复要求就是"批量 1-3s 随机延时防封"；蓝奏云 fileup.php 对高频连续上传的风控是真实存在的（LanZouCloud-API 原实现同样带延时）。当前形态等于把 V1 最核心的风控防护丢了。

**修复**：循环内 `if (index > 0) delay(Random.nextLong(1_000, 3_001))`，与 delete 同款。

### N2（P1）链式调度失败语义冲突——一批失败，后续整链静默不传

**位置**：`UploadViewModel.startUpload()`（WorkContinuation `then()` 链）+ `UploadWorker` 返回值

**推演**（WorkManager 文档语义：链中任一 work 失败，其依赖 work 直接标 FAILED 不执行）：
1. 用户选 120 个文件 → 3 批（50+50+20）链式入队；
2. 批 1 中 1 个文件网络抖动失败 → Worker 返回 `Result.failure()`；
3. 批 2、批 3 **被 WorkManager 标 FAILED，代码根本不执行**；
4. UI 三个 work 都到终态 → `checkAllFinished()` 触发，failedAccumulator 只有批 1 那 1 个文件名；
5. 用户看到"上传完成，部分文件失败" + 1 个失败文件——**实际 70 个文件从未尝试上传**，且无任何提示。

**修复**（两处配合改，缺一不可）：
- Worker：无论批次内是否有失败，一律返回 `Result.success()`（失败名单只走 outputData 传递，不借用 WorkManager 的失败语义）；
- ViewModel：`checkAllFinished` 的判定从 `workStates.values.all { SUCCEEDED }` 改为 `failedAccumulator.isEmpty()`；`observeWorks` 里读取 `KEY_FAILED_FILES` 的条件从 `state == FAILED` 放宽到 `isFinished`。

### N3（P2）失败批次的本地缓存文件也被清理——失败名单失去重试价值

**位置**：`UploadWorker.doWork()` 收尾清理段

**现状**：清理无条件执行 `paths.forEach { f.delete() }`——上传**失败**的文件本地副本也被删掉。用户看到失败名单想重试时，文件已经不在了，只能重新 SAF 手选。

**修复**：只删上传成功的文件。注意分卷映射：`results` 中分卷文件对应原始 path 的成功判定是 `splitResults.all { it.success }`（卷全成才可删原文件；部分成功的分卷也应保留原文件以便重试，只清 `.cloudbox_split_` 临时目录）。

### N4（P2）copyUriToCache 同名文件互相覆盖——V1 #30 回归

**位置**：`UploadViewModel.copyUriToCache()`

**现状**：缓存文件名就是显示名（`File(dir, safeName)`）。V2 版本里防覆盖的时间戳前缀（`${System.currentTimeMillis()}_`）本轮被删除且无替代。从不同目录选两个同名文件 → 第二次拷贝覆盖第一次 → 列表两条记录指向同一路径 → 同一内容上传两遍，另一文件丢失。

**附带**：`removeFile()` 只从列表移除不删缓存文件（V2 时代靠 Worker 清整个目录兜底，现在 Worker 只清自己 paths）→ 用户添加后移除的文件成为孤儿缓存，滞留 cache/uploads。cache 目录系统可回收，属 P3 附带。

**修复**：恢复唯一性前缀（时间戳或 UUID 短串）；`removeFile` 时若该 path 已不在列表且位于 uploads 目录可顺带删除。

### P3 / 观察项

1. **AI_MAINTENANCE.md 状态矛盾**：已写入 .gitignore 但文件仍被 git 跟踪（gitignore 对已跟踪文件无效），且本轮仍在继续提交更新。二选一：`git rm --cached AI_MAINTENANCE.md`（真·不入库）或从 .gitignore 删掉这行（承认它是仓库文档）。当前矛盾状态会误导后续维护者。
2. **UploadScreen 消息常驻**：本轮删掉了自动 dismiss 的 LaunchedEffect，`dismissMessage()` 在 UI 层已无调用方——上传结果提示会一直挂在页面底部直到下次上传覆盖。给结果区加个"知道了"按钮或几秒后自动清除。
3. **Worker 头部注释陈旧**：`KEY_FILE_PATHS：待上传文件路径列表（逗号分隔…）`——实际是 StringArray，且"单批 ≤50"的分批位置已移到 ViewModel，注释该同步。
4. **进度条 total 跳变**：全局 total 初始化为全部文件数，RUNNING 更新用的是批内 total——多批上传时进度分母会从 120 跳到 50 再跳到 20。汇总展示可接受，但建议 either 全局累计 or 明确标注"第 x/y 批"。
5. **observeRecords 自触发**（观察项，不阻断）：Room flow 的 map 内做 DownloadManager 同步查询，`realName` 变化时写库 → 写库再次触发 flow 发射。第二轮收敛（realName 已相同不再写），无死循环，但每次列表变化有 N 次冗余 DM 查询。后续若下载记录变多，考虑把状态同步挪到主动刷新/定时器。

---

## 三、修复清单（给修复 AI，按优先级）

| 序 | 改动 | 对应 |
|---|---|---|
| 1 | Worker 循环加 1-3s 随机延时（index>0 时） | N1 |
| 2 | Worker 一律返回 success + UI 失败判定改 failedAccumulator | N2 |
| 3 | 清理段只删成功文件（分卷按原文件全成才删） | N3 |
| 4 | copyUriToCache 唯一前缀 + removeFile 清孤儿 | N4 |
| 5 | AI_MAINTENANCE.md 跟踪状态二选一；消息 dismiss；注释更新 | P3×3 |

**验收标准**：
- CI 转绿出新 release（tag = versionName = run_number）；
- 实测推演路径：选 120 个文件（含 1 个必失败文件，如空文件/断网点）→ 批 1 报 1 失败后批 2/3 仍完整执行，失败名单准确；——这条用人工断网模拟批 1 中途失败即可；
- 选两个同名文件上传 → 云端两个文件、内容各自正确；
- 失败批次上传后本地缓存文件仍在，可直接重选重试。

---

*复审：Zero（外部复审 AI）· 2026-08-30 · 对照 commit b41a9fe。下轮以 N1-N4 + P3×3 销账。*
