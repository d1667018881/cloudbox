package com.cloudbox.app.core.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.room.withTransaction
import com.cloudbox.app.core.data.local.db.AppDatabase
import com.cloudbox.app.core.data.local.db.FavoriteShareEntity
import com.cloudbox.app.core.data.local.datastore.DomainConfigStore
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

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
    private val domainConfigStore: DomainConfigStore
) {

    /** 备份文件格式版本。将来结构变了要 +1，恢复时据此兼容旧文件 */
    private companion object {
        const val FORMAT_VERSION = 1
        const val KIND_TAG = "cloudbox_backup"
        /** 备份文件存放的子目录名（公共下载目录下 / 应用私有下载目录下同名） */
        const val BACKUP_DIR_NAME = "云匣备份"
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
        RestorePreview(
            favoriteCount = favArr.length(),
            settingCount = root.optJSONObject("settings")?.length() ?: 0,
            backupTime = root.optLong("backup_time", 0L),
            backupAppVersion = root.optString("backup_version", "未知"),
            backupVersionCode = root.optLong("backup_version_code", 0L)
        )
    }

    /** 恢复前的预览信息（不落盘） */
    data class RestorePreview(
        val favoriteCount: Int,
        val settingCount: Int,
        val backupTime: Long,
        val backupAppVersion: String,
        val backupVersionCode: Long
    )

    /**
     * 执行恢复。**会先清空现有收藏夹**（与备份内容对齐，避免残留旧条目）。
     *
     * 设置项与域名配置是"按 key 覆盖"而不是"先清空再写"：
     * 备份里没有的 key 保留当前值，这样即使备份来自旧版本、
     * 缺少后来新增的设置项，也不会把它们重置成默认值。
     *
     * @return 恢复摘要
     */
    suspend fun restore(json: String, appVersionName: String): Result<RestoreSummary> =
        runCatching {
            val preview = inspect(json).getOrThrow()
            val root = JSONObject(json)
            val favArr = root.optJSONArray("favorites") ?: JSONArray()

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
        context.cacheDir?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() }
    }.getOrDefault(0L)

    /** 清空应用缓存目录。返回清理出的字节数 */
    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = cacheSizeBytes()
        runCatching {
            context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        }
        // 清完重算：目录里可能有系统刚写回的文件，用差值更能反映真实释放量
        val after = cacheSizeBytes()
        (before - after).coerceAtLeast(0L)
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
        withContext(Dispatchers.IO) {
            settingsStore.resetAll()
            domainConfigStore.clearOverrides()
            db.withTransaction {
                db.favoriteShareDao().clearAll()
                db.directLinkDao().clearAll()
                db.fileCacheDao().clearAllCache()
                db.downloadRecordDao().clearAll()
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
            val out = File(dir, name).apply { writeText(json) }
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
