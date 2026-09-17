package com.cloudbox.app.common

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import java.io.File

/**
 * 本机已安装应用枚举（「上传已安装应用」功能的数据源）。
 *
 * ─────────────────────────────────────────────────────────────
 * 为什么需要这个功能
 * ─────────────────────────────────────────────────────────────
 * 用户想备份/分享某个 App 时，传统做法是先用第三方工具把它导出成 APK，
 * 再回到网盘 App 选文件上传——两步，且要装额外工具。
 * 这里直接把"已安装应用列表"当作文件源：选中即上传，等价于自动完成导出。
 *
 * ─────────────────────────────────────────────────────────────
 * 对齐原版（v1.3.4.9）
 * ─────────────────────────────────────────────────────────────
 * 原版在 `file.lua` 的「本机应用」目录里做了同一件事，字段全部照搬：
 * - `getInstalledPackages(0)` 枚举
 * - `applicationInfo.flags and FLAG_SYSTEM` 区分系统应用
 *   （对应原版设置项 `show_system_app`，见 ty_core.lua:709）
 * - `applicationInfo.publicSourceDir` / `sourceDir` 取 APK 路径
 * - 体积按 KB/MB/GB 换算（原版 fn78）
 *
 * ─────────────────────────────────────────────────────────────
 * 关于包可见性（重要）
 * ─────────────────────────────────────────────────────────────
 * Android 11+ 不声明 QUERY_ALL_PACKAGES 时，`getInstalledPackages` 只会
 * 返回自己 + 少数系统必需包 + 与本应用有交互的包，列表残缺到没法用。
 * 已在 AndroidManifest 声明该权限并写明用途；本类**只在用户主动打开
 * 「从已安装应用上传」时调用**，不做任何后台枚举。
 */
object InstalledApps {

    /**
     * 一条已安装应用记录。
     *
     * @param label       显示名（如「微信」）。取不到时回落到包名
     * @param packageName 包名（如 com.tencent.mm）
     * @param apkPath     主 APK 的绝对路径，来自 `applicationInfo.sourceDir`
     * @param apkSize     主 APK 字节数，取不到为 0
     * @param isSystem    是否为系统应用（FLAG_SYSTEM 命中）
     */
    data class AppEntry(
        val label: String,
        val packageName: String,
        val apkPath: String,
        val apkSize: Long,
        val isSystem: Boolean
    ) {
        /** 主 APK 的展示体积（对齐原版 fn78 的换算口径） */
        val sizeText: String get() = formatBytes(apkSize)
    }

    /**
     * 枚举本机已安装应用。
     *
     * @param includeSystem 是否包含系统应用（对应原版 `show_system_app` 开关）
     * @return 按显示名排序的列表；读取失败返回空列表（调用方据此提示，不崩溃）
     */
    fun list(context: Context, includeSystem: Boolean = false): List<AppEntry> {
        val pm = context.packageManager
        // getInstalledPackages(0) 与原版一致（0 = 不附加任何 flag，最省内存的形态）
        val packages = runCatching { pm.getInstalledPackages(0) }.getOrNull()
            ?: return emptyList()

        val self = context.packageName
        return packages.asSequence()
            .mapNotNull { pkg ->
                val ai = pkg.applicationInfo ?: return@mapNotNull null
                // 自己不上传自己：没有意义，而且正在运行的文件可能读不完整
                if (pkg.packageName == self) return@mapNotNull null

                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) return@mapNotNull null

                val apkPath = runCatching { ai.publicSourceDir ?: ai.sourceDir }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                val label = runCatching { pm.getApplicationLabel(ai).toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: pkg.packageName

                AppEntry(
                    label = label,
                    packageName = pkg.packageName,
                    apkPath = apkPath,
                    apkSize = runCatching { File(apkPath).length() }.getOrDefault(0L),
                    isSystem = isSystem
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    /**
     * 取应用图标。
     *
     * 放在这里而不是数据类里：Drawable 不参与 equals/hashCode，
     * 塞进 data class 会污染列表 diff（Compose 的 key 比较会失真）。
     */
    fun iconOf(context: Context, entry: AppEntry): Drawable? =
        runCatching {
            context.packageManager.getApplicationIcon(entry.packageName)
        }.getOrNull()

    /**
     * 给用户看的体积换算（对齐原版 fn78：≥1GB 用 GB，否则 ≥1MB 用 MB，
     * 否则 ≥1KB 用 KB，再否则用 B）。
     */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format("%.2f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format("%.2f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    /**
     * 复制应用 APK **到 [targetDir]**，文件名改为「应用名_包名尾.apk」。
     *
     * ─────────────────────────────────────────────────────────────
     * 为什么不直接把 sourceDir 交给上传链路
     * ─────────────────────────────────────────────────────────────
     * 两个独立的原因：
     *
     * 1. **文件名不可读**。系统装的 APK 一律叫 `base.apk`（分包还叫
     *    `split_config.arm64_v8a.apk`）。直接传上去，用户在网盘里看到一堆
     *    `base.apk`，完全分不清哪个是哪个应用 —— 这个功能的价值就没了。
     *
     * 2. **读权限不稳**。APK 位于 `/data/app/~~xxx/com.foo-xxx/base.apk`，
     *    虽然 sourceDir 通常全局可读，但在部分 ROM / SELinux 严格模式下
     *    直接读会 EACCES。先复制到自家缓存目录，读写都在沙箱内，稳定。
     *
     * 代价是多一次本地拷贝（几十 MB 级别，毫秒到几百毫秒），
     * 相对上面两个收益完全值得。注意这与 `enqueueUpload` 内部的
     * "拷贝到缓存"不重复：那一步是 Uri → 缓存，这里承担的是
     * **改名**和**跨沙箱读取**这两件事。
     *
     * @return 复制后的文件；失败返回 null（调用方据此提示，不崩）
     */
    fun copyToCache(entry: AppEntry, targetDir: File): File? =
        runCatching {
            val src = File(entry.apkPath)
            if (!src.exists() || !src.isFile) return null

            targetDir.mkdirs()
            // 文件名：应用名_包名后段.apk
            // 带上包名是因为不同应用重名很常见（"文件管理器"能有好几个），
            // 而包名唯一 —— 但包名通常很长（com.tencent.mm），所以只取最后一段。
            val safeLabel = entry.label.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
            val pkgTail = entry.packageName.substringAfterLast('.')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val out = File(targetDir, "${safeLabel}_${pkgTail}.apk")

            src.inputStream().use { ins ->
                out.outputStream().use { o -> ins.copyTo(o) }
            }
            out.takeIf { it.exists() && it.length() > 0 }
        }.getOrNull()
}
