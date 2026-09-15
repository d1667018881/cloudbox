package com.cloudbox.app.core.data.repository

import com.cloudbox.app.core.data.local.db.AppDatabase
import com.cloudbox.app.core.data.local.db.FavoriteShareEntity
import com.cloudbox.app.core.domain.model.FavoriteShare
import com.cloudbox.app.core.domain.repository.DirectLinkRepository
import com.cloudbox.app.core.domain.repository.ShareRepository
import com.cloudbox.app.core.domain.repository.UpdateCheckSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val directLinkRepo: DirectLinkRepository
) : ShareRepository {

    override fun observeFavorites(): Flow<List<FavoriteShare>> =
        db.favoriteShareDao().observeAll().map { list -> list.map { it.toModel() } }

    override suspend fun addFavorite(url: String, name: String, remark: String) {
        // ⚠️ 重新收藏同一条链接时不要覆盖已有的置顶状态——
        //    REPLACE 会整行替换，如果这里传默认 pinned=false，
        //    用户"重新收藏"会把置顶悄悄清掉。先读旧值，有则沿用。
        val old = db.favoriteShareDao().get(url)
        db.favoriteShareDao().insert(
            FavoriteShareEntity(
                shareUrl = url,
                name = name,
                remark = remark,
                pinned = old?.pinned ?: false,
                // V30：保留旧的检查结果，避免重新收藏把红点/时间戳抹掉
                kind = old?.kind ?: "file",
                pass = old?.pass ?: "",
                hasUpdate = old?.hasUpdate ?: false,
                lastCheckAt = old?.lastCheckAt ?: 0L
            )
        )
    }

    override suspend fun removeFavorite(url: String) {
        db.favoriteShareDao().delete(url)
        snapshots.remove(url)
    }

    override suspend fun updateRemark(url: String, remark: String) {
        db.favoriteShareDao().updateRemark(url, remark)
    }

    override suspend fun updateName(url: String, name: String) {
        db.favoriteShareDao().updateName(url, name)
    }

    override suspend fun setPinned(url: String, pinned: Boolean) {
        db.favoriteShareDao().updatePinned(url, pinned)
    }

    // ==================== V30：检查收藏文件夹更新 ====================

    /**
     * 逐个检查文件夹型收藏是否有更新。
     *
     * ─────────────────────────────────────────────────────────────
     * 实现依据：原版 v1.3.4.9 `home_func.lua` proto[35]（line 3984-4140）反汇编还原。
     * ─────────────────────────────────────────────────────────────
     * 原版流程：
     *   1. 只取 `type == "folder"` 的收藏，逐条处理
     *   2. 取分享页 → POST filemoreajax.php 翻页拿全量文件列表
     *   3. 与上次记录的文件集合比对，有变化 → `update = true` + 列表红点
     *      （原版常量：`" 发现 "` + n + `" 项更新"` / `" 未检测到更新"`）
     *
     * ⚠️ 为什么复用 resolveFolder 而不是照抄原版的 filemoreajax 调用：
     *    原版是**内联**在 home_func.lua 里手写 HTTP + 正则（proto[35] 有 413 条指令
     *    几乎全是字符串处理）。cloudbox 的 resolveFolder 已把这套协议做成了
     *    带翻页、带提取码、带反爬挑战处理的完整实现（V21-V27 反复实测过）。
     *    再抄一遍只会多一份会腐烂的重复代码 —— 行为等价，且更健壮。
     *
     * 串行 + 条目间延时：原版自己也是逐条 `Ticker` 串行跑（进度条 `更新批量进度1`），
     * 且蓝奏云对同 IP 高频请求有风控，并发只会更快触发挑战。
     */
    override suspend fun checkFolderUpdates(
        onProgress: (done: Int, total: Int, current: String) -> Unit
    ): Result<UpdateCheckSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val folders = db.favoriteShareDao().getFolders()
            if (folders.isEmpty()) return@runCatching UpdateCheckSummary(0, 0, 0)

            var checked = 0
            var updated = 0
            val failedNames = mutableListOf<String>()

            folders.forEachIndexed { index, fav ->
                onProgress(index, folders.size, fav.name)
                val links = runCatching {
                    directLinkRepo.resolveFolder(fav.shareUrl, fav.pass).getOrThrow().links
                }.getOrNull()

                if (links != null) {
                    val signature = signatureOf(links.map { it.fileName })
                    // 有更新 = 文件集合与上次不同。
                    // 首次检查（lastCheckAt == 0）只建立基线、**不报**"有更新"——
                    // 否则用户一装好 App 就满屏红点，那是噪音不是信号。
                    val changed = fav.lastCheckAt != 0L && signature != snapshots[fav.shareUrl]
                    if (changed) updated++
                    // 结果 + 时间戳一次落库，避免出现「红点亮了但时间戳是旧的」不一致
                    db.favoriteShareDao()
                        .updateCheckResult(fav.shareUrl, changed, System.currentTimeMillis())
                    snapshots[fav.shareUrl] = signature
                    checked++
                } else {
                    failedNames.add(fav.name)
                }
                if (index < folders.size - 1) delay(INTERVAL_MS)
            }

            onProgress(folders.size, folders.size, "")
            UpdateCheckSummary(
                checked = checked,
                updated = updated,
                failed = failedNames.size,
                failedNames = failedNames
            )
        }
    }

    override suspend fun clearUpdateFlag(url: String) {
        val old = db.favoriteShareDao().get(url) ?: return
        db.favoriteShareDao().updateCheckResult(url, false, old.lastCheckAt)
    }

    override suspend fun correctKind(url: String, kind: String, pass: String) {
        db.favoriteShareDao().updateKindAndPass(url, kind, pass)
    }

    // ==================== 内部 ====================

    /** 条目间延时，避开风控（与 resolveBatch 同量级） */
    private val INTERVAL_MS = 1200L

    /**
     * 文件集合快照（判断"有没有更新"的基线）。
     *
     * 存在内存而不是数据库：这只是"上次检查时看到的样子"，属于可再生的派生数据，
     * 丢了最多让下次检查退化成"重新建立基线"（少报一次更新），不影响正确性。
     * 为此单开一张表 + 迁移不划算 —— 收藏夹本身的改动已经够多了。
     *
     * ⚠️ 进程被杀后快照会丢，表现为「重启后第一次检查不报更新」。
     *    这是有意的取舍：宁可漏报一次，也不要误报（误报会让用户以为收藏在变）。
     */
    private val snapshots = ConcurrentHashMap<String, String>()

    private fun signatureOf(names: List<String>): String = names.sorted().joinToString("|")

    /** Entity → UI 模型 */
    private fun FavoriteShareEntity.toModel() = FavoriteShare(
        shareUrl = shareUrl,
        name = name,
        remark = remark,
        createdAt = createdAt,
        pinned = pinned,
        kind = kind,
        pass = pass,
        hasUpdate = hasUpdate,
        lastCheckAt = lastCheckAt
    )
}
