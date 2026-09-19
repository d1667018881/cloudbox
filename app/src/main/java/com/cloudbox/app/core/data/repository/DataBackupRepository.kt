package com.cloudbox.app.core.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.cloudbox.app.core.data.local.db.AppDatabase
import com.cloudbox.app.core.data.local.db.FavoriteShareEntity
import com.cloudbox.app.core.data.local.datastore.DomainConfigStore
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.domain.repository.DownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWith

/**
 * 数据备份 / 恢复 / 清理（V32 新增）。
 *
 * ─────────────────────────────────────────────────────────────
 * 对齐原版
 * ─────────────────────────────────────────────────────────────
 * 原版在「隐私设置」与「错误页」各有一个「备份数据 / 重置应用」入口
 * （privacy_settings_layout.lua:701 / 1621，error_page_layout.lua:501 / 610）。
 * 备份是一个 JSON 文件，含 `backup_time` / `backup_version` /
 * `backup_version_code` / `backup_lock` / `backup_password` 元信息
 * （func.lua:4280-4295 的恢复流程会读这几项做校验）。
 * 本实现保留同样的元信息结构，便于将来互通。
 *
 * ─────────────────────────────────────────────────────────────
 * 备份什么、不备份什么（这是个刻意的取舍）
 * ─────────────────────────────────────────────────────────────
 * **备份**（用户积累的、丢了要重新手工建的）：
 * - 收藏夹（含备注、置顶、提取码、更新红点状态）
 * - 全部设置项（SettingsStore）
 * - 域名配置覆盖（DomainConfigStore）
 *
 * **不备份**（可从服务端重新拉取的缓存）：
 * - 直链缓存（direct_link_cache）—— 到期即失效，重解析就有
 * - 文件列表缓存（cloud_files）—— 刷新即重建
 * - 下载记录（download_records）—— 反映的是本机下载历史，跨机恢复没意义
 * - 搜索索引（file_search_fts）—— 由文件列表派生
 *
 * 理由：备份文件应该小、可读、只装"用户真正在乎的东西"。
 * 把几万条文件缓存塞进去，备份文件动辄几 MB，用户既看不懂也没法检查。
 *
 * **也不备份账号密码与 Cookie**：那是凭据，明文写进一个用户会随手
 * 分享/存网盘的文件里是严重的安全问题。恢复到新设备重新登录一次即可。
 */
@Singleton
class DataBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val domainConfigStore: DomainConfigStore,
    /**
     * 只为了「重置应用」时正确地清空下载。
     *
     * 为什么不在参数里凑合着用 DAO：清空下载不只是删数据库行，
     * 还必须取消系统 DownloadManager 的任务（见 [resetAppData] 注释）。
     * 那段逻辑已经在 [DownloadRepository.clearAll] 里实现且被下载页
     * 使用，直接复用比在这里重写一遍更可靠 —— 重写就意味着
     * 将来下载模块改了清理语义，这里会静默地不同步。
     */
    private val downloadRepository: DownloadRepository,
    /**
     * 只为了「清缓存 / 重置」前确认没有在途上传（见 [clearCache] 的说明）。
     *
     * Hilt 已通过 AppModule.provideWorkManager 提供它（UploadViewModel 也在用）。
     */
    private val workManager: WorkManager
) {

    /** 备份文件格式版本。将来结构变了要 +1，恢复时据此兼容旧文件 */
    private companion object {
        const val FORMAT_VERSION = 1
        const val KIND_TAG = "cloudbox_backup"
        /** 备份文件存放的子目录名（公共下载目录下 / 应用私有下载目录下同名） */
        const val BACKUP_DIR_NAME = "云匣备份"
        const val TAG = "CloudBoxBackup"
    }

    /**
     * 备份结果的摘要，供 UI 展示"备了什么"。
     */
    data class BackupSummary(
        val favoriteCount: Int,
        val settingCount: Int,
        val hasDomainOverride: Boolean
    )

    /** 恢复结果的摘要 */
    data class RestoreSummary(
        val favoriteCount: Int,
        /** 备份文件的生成时间（毫秒），用于提示用户这份备份有多旧 */
        val backupTime: Long,
        val backupAppVersion: String
    )

    /**
     * 生成备份内容。
     *
     * @return JSON 文本（已格式化，便于用户打开检查）
     */
    suspend fun buildBackupJson(appVersionName: String): Pair<String, BackupSummary> =
        withContext(Dispatchers.IO) {
            val favorites = db.favoriteShareDao().getAllOnce()
            val settings = settingsStore.snapshotAll()
            val domainOverride = domainConfigStore.snapshotOverrides()

            val root = JSONObject().apply {
                // —— 元信息（对齐原版 func.lua 的 恢复数据.* 字段）——
                put("kind", KIND_TAG)
                put("format_version", FORMAT_VERSION)
                put("backup_time", System.currentTimeMillis())
                put("backup_version", appVersionName)
                put(
                    "backup_version_code",
                    runCatching {
                        context.packageManager
                            .getPackageInfo(context.packageName, 0).longVersionCode
                    }.getOrDefault(0L)
                )

                // —— 收藏夹 ——
                put("favorites", JSONArray().apply {
                    favorites.forEach { f ->
                        put(JSONObject().apply {
                            put("share_url", f.shareUrl)
                            put("name", f.name)
                            put("remark", f.remark)
                            put("created_at", f.createdAt)
                            put("pinned", f.pinned)
                            put("kind", f.kind)
                            put("pass", f.pass)
                            put("has_update", f.hasUpdate)
                            put("last_check_at", f.lastCheckAt)
                        })
                    }
                })

                // —— 设置 ——
                put("settings", JSONObject().apply {
                    settings.forEach { (k, v) -> put(k, v) }
                })

                // —— 域名覆盖 ——
                put("domain_override", JSONObject().apply {
                    domainOverride.forEach { (k, v) -> put(k, v) }
                })
            }

            root.toString(2) to BackupSummary(
                favoriteCount = favorites.size,
                settingCount = settings.size,
                hasDomainOverride = domainOverride.isNotEmpty()
            )
        }

    /**
     * 解析并校验备份文本；不写入任何东西。
     *
     * 拆成"先看后写"两步的原因：恢复是**破坏性操作**（会清空现有收藏），
     * 必须先让用户看到"这份备份是什么时候的、有多少条"再确认，
     * 而不是选完文件直接覆盖。
     */
    fun inspect(json: String): Result<RestorePreview> = runCatching {
        val root = JSONObject(json)
        if (root.optString("kind") != KIND_TAG) {
            throw IllegalArgumentException("这不是云匣的备份文件")
        }
        val fmt = root.optInt("format_version", 0)
        if (fmt > FORMAT_VERSION) {
            throw IllegalArgumentException(
                "备份文件来自更新的版本（格式 $fmt，本版支持 $FORMAT_VERSION），请升级 App 后再恢复"
            )
        }
        val favArr = root.optJSONArray("favorites") ?: JSONArray()
        val settingCount = root.optJSONObject("settings")?.length() ?: 0
        RestorePreview(
            favoriteCount = favArr.length(),
            settingCount = settingCount,
            backupTime = root.optLong("backup_time", 0L),
            backupAppVersion = root.optString("backup_version", "未知"),
            backupVersionCode = root.optLong("backup_version_code", 0L),
            // 空备份标记：UI 据此弹强警告，且要求用户显式确认后才允许恢复
            isEmpty = favArr.length() == 0 && settingCount == 0
        )
    }

    /** 恢复前的预览信息（不落盘） */
    data class RestorePreview(
        val favoriteCount: Int,
        val settingCount: Int,
        val backupTime: Long,
        val backupAppVersion: String,
        val backupVersionCode: Long,
        /** 收藏与设置都是空的 —— 恢复它等于清空当前数据（见 [restore] 的说明） */
        val isEmpty: Boolean
    )

    /**
     * 执行恢复。**会先清空现有收藏夹**（与备份内容对齐，避免残留旧条目）。
     *
     * 设置项与域名配置是"按 key 覆盖"而不是"先清空再写"：
     * 备份里没有的 key 保留当前值，这样即使备份来自旧版本、
     * 缺少后来新增的设置项，也不会把它们重置成默认值。
     *
     * ─────────────────────────────────────────────────────────────
     * 关于 [allowEmptyFavorites]
     * ─────────────────────────────────────────────────────────────
     * 这个参数存在的唯一理由是**保护用户数据**。
     *
     * 正常情况下，一份既没有收藏、又没有设置的"备份"，只可能是
     * 用户在 App 里**什么都没攒下**的时候导出的（新装、刚重置），
     * 用它恢复没有任何损失。
     *
     * 但还有一种可能：文件被截断/损坏到只剩元信息外壳，
     * `inspect()` 能过（`kind` 字段还在），favorites 与 settings 都是空。
     * 此时若照常执行，就会**用空内容把用户真实的收藏夹整个清掉** ——
     * 而用户的本意是"恢复备份"，得到的结果却是"删光收藏"，
     * 且不可撤销。这种情况必须拦下来。
     *
     * 所以默认（false）拒绝"两样都空"的备份；UI 侧在预览弹窗里
     * 明确告知用户"这份备份是空的"，由用户显式确认后才用 true 调用。
     *
     * @return 恢复摘要
     */
    suspend fun restore(
        json: String,
        allowEmptyFavorites: Boolean = false
    ): Result<RestoreSummary> =
        runCatching {
            val preview = inspect(json).getOrThrow()
            val root = JSONObject(json)
            val favArr = root.optJSONArray("favorites") ?: JSONArray()

            // 空备份保护：收藏与设置都是空的，说明这份备份没有可恢复的内容。
            // 继续执行只会造成破坏（清空现有收藏），没有任何收益。
            if (preview.favoriteCount == 0 && preview.settingCount == 0 && !allowEmptyFavorites) {
                throw IllegalArgumentException(
                    "这份备份里没有收藏也没有设置，恢复它会把当前内容清空。" +
                        "请确认文件是否完整，或改用「重置应用」达到同样目的"
                )
            }

            // 收藏夹：整体替换（放在事务里，中途失败不会留下半套数据）
            val items = (0 until favArr.length()).mapNotNull { i ->
                favArr.optJSONObject(i)?.let { o ->
                    val url = o.optString("share_url").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    FavoriteShareEntity(
                        shareUrl = url,
                        name = o.optString("name"),
                        remark = o.optString("remark"),
                        createdAt = o.optLong("created_at", System.currentTimeMillis()),
                        pinned = o.optBoolean("pinned", false),
                        kind = o.optString("kind").ifBlank { "file" },
                        pass = o.optString("pass"),
                        hasUpdate = o.optBoolean("has_update", false),
                        lastCheckAt = o.optLong("last_check_at", 0L)
                    )
                }
            }
            db.withTransaction {
                db.favoriteShareDao().clearAll()
                db.favoriteShareDao().insertAll(items)
            }

            // 设置：按 key 覆盖
            root.optJSONObject("settings")?.let { s ->
                val map = mutableMapOf<String, String>()
                s.keys().forEach { k -> map[k] = s.optString(k) }
                if (map.isNotEmpty()) settingsStore.applySnapshot(map)
            }

            // 域名覆盖：同样按 key 覆盖
            root.optJSONObject("domain_override")?.let { d ->
                val map = mutableMapOf<String, String>()
                d.keys().forEach { k -> map[k] = d.optString(k) }
                if (map.isNotEmpty()) domainConfigStore.applyOverrides(map)
            }

            RestoreSummary(
                favoriteCount = items.size,
                backupTime = preview.backupTime,
                backupAppVersion = preview.backupAppVersion
            )
        }

    // ==================== 清理 ====================

    /**
     * 缓存目录占用字节数（含上传中间文件、分卷临时目录、APK 拷贝副本等）。
     *
     * 只统计 cacheDir：外部存储上的下载文件是用户主动下载的成果，
     * 不属于"缓存"，不能在"清缓存"里被删掉。
     */
    fun cacheSizeBytes(): Long = runCatching {
        // 注意 `?.` 链会把整个表达式变成可空（`walkBottomUp()` 为 null 时
        // `sumOf` 根本不会调用，结果是 null 而不是 0），而函数签名要 Long。
        // 所以这里显式走 `orEmpty()` 把序列兜成空序列，sumOf 才能返回 0L。
        context.cacheDir?.walkBottomUp().orEmpty()
            .filter { it.isFile }
            .sumOf { it.length() }
    }.getOrDefault(0L)

    /**
     * 清空应用缓存目录。返回清理出的字节数。
     *
     * ─────────────────────────────────────────────────────────────
     * ⚠️ 上传进行中时**必须拒绝**
     * ─────────────────────────────────────────────────────────────
     * 待上传文件的唯一副本就放在 `cacheDir/uploads/<uuid>/` 下（见
     * `UploadViewModel.copyUriToCache`）。而这里做的是 `cacheDir` 全量删除，
     * 于是会出现这样一条真实路径：
     *
     * ```
     * 用户 FAB 选 10 个文件 → 已拷进 cacheDir → 切到「我的」→ 设置 → 清除缓存
     *   → uploads/ 被整体删掉
     *   → Worker 执行时 File.exists() == false
     *   → 全部计入失败名单「本地缓存文件已丢失，请重新选择后上传」
     * ```
     *
     * 结果不算"假成功"（这一点 V5 已经修好了，Worker 会如实上报失败），
     * 但用户刚选完的一整批文件**无声变砖**，且提示是"请重新选择"——
     * 他刚选过。清缓存这个动作本身就写着"清理上传中间文件"，
     * 用户不会预期它把**还没传上去的**文件也清掉。
     *
     * 所以这里用 WorkManager 查一次在途上传：只要还有未完成的批次，
     * 就抛异常让调用方把原因如实告诉用户。[resetAppData] 也走这个函数，
     * 因此"重置应用"同样被保护 —— 重置连 DB 一起清，再删掉待传文件，
     * 用户的损失不只是一批文件。
     *
     * @throws IllegalStateException 有上传任务在途时
     */
    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        if (hasUploadInFlight()) {
            throw IllegalStateException(
                "有文件正在上传，现在清缓存会把还没传上去的文件删掉。" +
                    "请等上传结束后再清理"
            )
        }
        val before = cacheSizeBytes()
        runCatching {
            context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        }
        // 清完重算：目录里可能有系统刚写回的文件，用差值更能反映真实释放量
        val after = cacheSizeBytes()
        (before - after).coerceAtLeast(0L)
    }

    /**
     * 是否还有上传批次未到终态。
     *
     * 判定口径与 `UploadViewModel.init` 的"会话恢复"完全一致：按
     * `UploadWorker.TAG_UPLOAD_SESSION` 取全部批次，只要有一条
     * `!state.isFinished`（ENQUEUED / RUNNING / BLOCKED）就算在途。
     *
     * 为什么要带上 ENQUEUED：离线时批次会停在 ENQUEUED 等网络，
     * 那个状态下文件同样还没传上去，缓存文件一样不能删。
     *
     * 取不到 WorkManager（初始化异常等）时返回 true —— **宁可误报"正在上传"
     * 拦住清理，也不能误判为"没有上传"把用户的文件删掉**。清理缓存晚做一次
     * 没有任何代价，误删一次是不可逆的。
     */
    private suspend fun hasUploadInFlight(): Boolean = runCatching {
        val tag = com.cloudbox.app.feature.upload.UploadWorker.TAG_UPLOAD_SESSION
        val future = workManager.getWorkInfosByTag(tag)
        val infos = suspendCancellableCoroutine { cont ->
            future.addListener(
                { cont.resumeWith(runCatching { future.get() }) },
                ContextCompat.getMainExecutor(context)
            )
        }
        infos.any { !it.state.isFinished }
    }.getOrElse { e ->
        Log.w(TAG, "查询上传任务状态失败（按「有上传在途」处理）：${e.javaClass.simpleName} ${e.message}")
        true
    }

    /**
     * 重置应用数据（保留登录态）。
     *
     * ─────────────────────────────────────────────────────────────
     * 为什么不连账号一起清
     * ─────────────────────────────────────────────────────────────
     * 原版的「重置应用」是错误页里的救援手段，属于"App 起不来了"场景，
     * 那时本来也进不去。放在设置页里，用户点它是想"把乱七八糟的配置
     * 恢复原样"，不是想退出登录 —— 真要退出登录，账号管理区有单独的
     * 「删除」按钮。把两者混在一起，用户点一下就被登出，是很差的体验。
     *
     * 清除范围：
     * - 全部设置项 → 回默认值
     * - 域名配置覆盖 → 清空（回落远程/内置默认）
     * - 收藏夹 → 清空
     * - 数据库缓存表 → 清空（直链缓存 / 文件列表 / 下载记录 / 搜索索引）
     * - 缓存目录 → 清空
     */
    suspend fun resetAppData(): Result<Unit> = runCatching {
        // ⚠️ 有上传在途时**整体拒绝**，而且必须查在**动手之前**。
        //
        //    不能依赖末尾那句 clearCache() 来兜底：它是最后一步，
        //    此时设置、收藏夹、数据库**都已经被清空了**，如果它再抛异常，
        //    用户拿到的是"重置失败"，但实际上数据已经没了 ——
        //    半完成状态比干脆没做更糟：用户以为没重置成功，其实已经重置了。
        //
        //    所以先检查一次，早失败、不产生任何副作用。
        if (hasUploadInFlight()) {
            throw IllegalStateException(
                "有文件正在上传，现在重置会把这些还没传上去的文件一起清掉。" +
                    "请等上传结束后再重置"
            )
        }
        withContext(Dispatchers.IO) {
            settingsStore.resetAll()
            domainConfigStore.clearOverrides()
            // 下载记录必须走 DownloadRepository.clearAll()，**不能**直接调
            // DownloadRecordDao.clearAll()。
            //
            // 区别在于前者会先 `downloadManager.remove(record.downloadId)`
            // 取消系统下载任务，再清表；后者只清表。直接用 DAO 的话，
            // 重置后系统下载管理器里**仍在跑的任务会继续下载**，
            // 但 App 的下载列表已经空了 —— 用户看到通知栏有进度、
            // 进去却找不到这条记录，也没法取消。变成"幽灵下载"。
            downloadRepository.clearAll()
            db.withTransaction {
                db.favoriteShareDao().clearAll()
                db.directLinkDao().clearAll()
                db.fileCacheDao().clearAllCache()
                db.searchIndexDao().clearAll()
            }
        }
        clearCache()
    }

    // ==================== 备份文件读写 ====================

    /**
     * 把备份写成一个真实文件，返回 (文件, 摘要)。
     *
     * ─────────────────────────────────────────────────────────────
     * 写到哪儿、为什么要试两个地方
     * ─────────────────────────────────────────────────────────────
     * 首选 `Downloads/云匣备份/`：
     * - 备份是用户**要长期留着**的东西，放 cacheDir 迟早会被系统清理掉；
     * - 放公共下载目录，用户不装文件管理器也能在「文件」App 里找到，
     *   想拷到电脑/网盘直接就能拷。
     *
     * 但 `Environment.getExternalStoragePublicDirectory` 在 Android 10+
     * 已废弃，Android 11+ 上写入是否成功取决于 ROM 是否放行（分区存储
     * 严格实现会直接抛 EACCES）。所以失败时**降级**到自己应用的外部私有目录
     * `Android/data/<pkg>/files/Download/`，并让 UI 如实说明"文件在哪、
     * 建议手动拷走" —— 而不是报一句"备份失败"让用户白忙。
     *
     * 不覆盖同名文件而是带上时间戳：备份往往是"重置前存一份、折腾完再存一份"，
     * 同名覆盖会让用户丢掉更早那份能救命的备份。时间戳精确到秒足够用，
     * 同一秒内点两次是不可达的（按钮在写入期间处于 loading）。
     */
    suspend fun writeBackupFile(appVersionName: String): Pair<File, BackupSummary> =
        withContext(Dispatchers.IO) {
            val (json, summary) = buildBackupJson(appVersionName)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "cloudbox_backup_$stamp.json"

            val dir = resolveBackupDir()
            val out = File(dir, name)

            // 先写临时文件、校验通过后再改名成正式名。
            //
            // 为什么要这一步：`writeText` 只保证"调用没有抛异常"，
            // 不保证"目标文件内容完整"。存储写满、文件系统报错被吞、
            // 进程在写到一半时被杀 —— 这些情况都可能留下一个**截断的
            // JSON**。而截断的备份文件对一个已经重置过 App 的用户来说
            // 等于没备份（他以为自己有救命稻草，其实没有）。
            //
            // 做法：写 .tmp → 回读校验（长度一致 + 能解析 + kind 正确）
            // → 改名。改名在同一个文件系统内是原子的，所以用户看到的
            // 永远是一个完整的文件，不存在"半截备份"这个中间态。
            val tmp = File(dir, "$name.tmp")
            try {
                tmp.writeText(json)

                // 回读校验：不信 writeText 的返回值，直接看磁盘上到底是什么
                val written = tmp.readText()
                if (written.length != json.length) {
                    throw IllegalStateException(
                        "备份写入不完整（应写 ${json.length} 字节，实际 ${written.length} 字节），" +
                            "可能是存储空间不足"
                    )
                }
                // 再解析一次：长度一致但内容损坏（编码问题等）也要拦住
                inspect(written).getOrThrow()

                if (!tmp.renameTo(out)) {
                    throw IllegalStateException("备份文件改名失败（目标目录可能不可写）")
                }
            } catch (e: Throwable) {
                // 失败就清掉临时文件，不留下 .tmp 垃圾
                runCatching { tmp.delete() }
                throw e
            }

            out to summary
        }

    /** 备份目录：优先公共下载目录，不可用时降级到应用外部私有目录 */
    private fun resolveBackupDir(): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            BACKUP_DIR_NAME
        )
        if (publicDir.exists() || publicDir.mkdirs()) {
            // mkdirs 成功不代表可写（部分 ROM 上目录存在但无权写文件），
            // 用一次真实的探针写来确认，探针文件立刻删掉。
            val probe = File(publicDir, ".probe")
            if (runCatching { probe.writeText(""); probe.delete(); true }.getOrDefault(false)) {
                return publicDir
            }
        }
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), BACKUP_DIR_NAME)
            .apply { mkdirs() }
    }

    /**
     * 给用户看的存放位置说明。
     *
     * 公共目录路径要按 用户可读 的形式给出（`Download/云匣备份/xxx.json`），
     * 而不是 `Environment.getExternalStoragePublicDirectory()` 返回的
     * `/storage/emulated/0/Download/...` —— 后者对普通用户是天书，
     * 只有降级到私有目录时才必须给出完整路径（因为那个目录他得去翻）。
     */
    fun describeLocation(file: File): String {
        val publicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.absolutePath
        return if (publicRoot != null && file.absolutePath.startsWith(publicRoot)) {
            val rel = file.absolutePath.removePrefix(publicRoot).trimStart('/')
            "Download/$rel"
        } else {
            "${file.absolutePath}\n（此目录在「文件」App 里不易找到，建议尽快拷走）"
        }
    }

    /**
     * 读取用户选中的备份文件文本（SAF 提供的是 content:// uri）。
     *
     * 不做任何解析，只负责把字节读成字符串 —— 解析交给 [inspect]，
     * 这样"读失败"和"格式不对"能给出不同的错误文案。
     */
    suspend fun readBackupText(uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw IllegalStateException("无法读取所选文件")
            }
        }
}
