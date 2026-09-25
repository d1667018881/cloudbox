package com.cloudbox.app.core.data.local.iconpack

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cloudbox.app.core.domain.model.IconPack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 图标包存储（原版 `getExternalFilesDir("icon_pack")`，home_func.lua:113 / customize_settings.lua:1504-1535）。
 *
 * 导入 = 把 zip 解压到 `getExternalFilesDir/icon_pack/<包名>/`，并要求根目录有 `info.json`。
 *
 * ⚠️ 与蓝云原版的一处**有意偏离**：原版解压用 `File(baseDir, entry.name)`，
 * **没有 zip-slip 防护**（恶意 zip 可写到包目录之外）。这里对每个条目做
 * canonicalPath 前缀校验，并限制条目总数。
 */
@Singleton
class IconPackStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val rootDir: File
        get() = File(context.getExternalFilesDir(null), ROOT_DIR_NAME).apply { mkdirs() }

    /** 枚举已导入的包（逐个读 info.json；无 info.json 的目录跳过） */
    fun listPacks(): List<IconPack> =
        rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val info = File(dir, INFO_FILE)
                if (!info.isFile) return@mapNotNull null
                runCatching {
                    val json = JSONObject(info.readText())
                    IconPack(
                        name = json.optString("name", dir.name),
                        version = json.optString("ver", "0"),
                        author = json.optString("author", ""),
                        path = dir.absolutePath
                    )
                }.getOrNull()
            }
            ?.sortedBy { it.name }
            .orEmpty()

    /** 从选中的 zip 导入，返回新导入的包 */
    suspend fun importZip(uri: Uri): Result<IconPack> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(uri) ?: "icon_pack"
            val baseName = displayName.removeSuffix(".zip").ifBlank { "icon_pack" }
            var target = File(rootDir, baseName)
            if (target.exists()) {
                // 同名包：追加时间戳（对齐原版 customize_settings.lua:1517-1535）
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                target = File(rootDir, "${baseName}_$ts")
            }
            target.mkdirs()
            val baseCanonical = target.canonicalFile
            val basePrefix = baseCanonical.path + File.separator

            val stream = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            stream.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    var count = 0
                    var totalBytes = 0L
                    while (entry != null) {
                        if (++count > MAX_ENTRIES) error("图标包条目过多，已中止")
                        val outFile = File(target, entry.name).canonicalFile
                        if (!outFile.path.startsWith(basePrefix)) {
                            error("图标包含非法路径：${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            // V40（N3）：zis.copyTo 不看声明大小（声明值可伪造），
                            // 按实际写出累计，超限即弃 —— 防 zip bomb 把外部存储写满
                            outFile.outputStream().use { out ->
                                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n < 0) break
                                    totalBytes += n
                                    if (totalBytes > MAX_TOTAL_BYTES) {
                                        error("图标包解压总量超过上限，已中止")
                                    }
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            // .nomedia：避免图标出现在系统相册（对齐原版规范说明）
            runCatching { File(target, ".nomedia").writeText("") }

            val info = File(target, INFO_FILE)
            if (!info.isFile) {
                target.deleteRecursively()
                error("不是有效的图标包（缺少 info.json）")
            }
            val json = JSONObject(info.readText())
            val name = json.optString("name", baseName).trim()
            if (name.isBlank()) {
                target.deleteRecursively()
                error("图标包 info.json 缺少 name")
            }
            IconPack(
                name = name,
                version = json.optString("ver", "0"),
                author = json.optString("author", ""),
                path = target.absolutePath
            )
        }
    }

    /** 删除已导入的包（不允许删到 icon_pack 目录之外） */
    fun deletePack(path: String): Result<Unit> = runCatching {
        val root = rootDir.canonicalFile
        val target = File(path).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) error("只能删除已导入的图标包")
        if (!target.exists()) error("图标包不存在")
        if (!target.deleteRecursively()) error("删除失败")
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    private companion object {
        const val ROOT_DIR_NAME = "icon_pack"
        const val INFO_FILE = "info.json"
        const val MAX_ENTRIES = 2000
        /** 解压总量上限（V40 N3）：图标包全部是几十 KB 的 PNG，50MB 已是极宽松上限 */
        const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    }
}
