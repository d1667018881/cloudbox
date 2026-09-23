package com.cloudbox.app.common

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import java.io.File
import java.util.Locale

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
 *   （对应原版设置项 `show_system_app`，见 ty_core.lua:995-996，默认 false）
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
     * APK 副本的落盘目录（`cacheDir/uploaded_apps`）。
     *
     * 提成公共函数是为了让"写"和"清"两处用同一个常量：
     * 目录名散落在两个文件里手写字符串，改一处忘另一处就会
     * 出现"清了但没清干净"这种极难发现的问题。
     */
    fun appCopyDir(context: Context): File = File(context.cacheDir, "uploaded_apps")

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
     *
     * 显式指定 `Locale.US`：`String.format` 不带 Locale 会用系统默认，
     * 在阿拉伯语 / 波斯语等使用本国数字的环境下会输出 `١٢.٣٤ MB`
     * （东阿拉伯数字），中文用户看到的是一串看不懂的字符。
     * 体积数值属于"技术数字"，不随界面语言本地化，固定用 ASCII 数字。
     */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.2f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    /**
     * 算出某个应用对应的副本文件路径（不创建、不拷贝）。
     *
     * 单独提出来，是为了让"拷贝前先登记在途文件"和"真正执行拷贝"
     * 两处对**目标路径的判断完全一致**。若各写一遍文件名拼接规则，
     * 一旦将来改动（比如加长 `take(40)`），两处会悄悄不一致，
     * 结果是"登记的文件名"和"实际写入的文件名"不同 →
     * 防竞态的跳过逻辑失效 → 又回到"拷贝途中被删"的 bug。
     */
    fun targetFileFor(entry: AppEntry, targetDir: File): File {
        // 文件名：应用名_包名后段.apk
        // 带上包名是因为不同应用重名很常见（"文件管理器"能有好几个），
        // 而包名唯一 —— 但包名通常很长（com.tencent.mm），所以只取最后一段。
        val safeLabel = entry.label.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
        val pkgTail = entry.packageName.substringAfterLast('.')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return File(targetDir, "${safeLabel}_${pkgTail}.apk")
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
     * ─────────────────────────────────────────────────────────────
     * ⚠️ 残留副本由 [purgeStaleCopies] 在下次打开选择器时清理
     * ─────────────────────────────────────────────────────────────
     * 这里产出的是一份**完整 APK 副本**（几十 MB），而 `enqueueUpload`
     * 内部还会再拷一份到 `uploads/<uuid>/`（那份由 Worker 自己回收）。
     *
     * 那这份副本谁来清？**不能在上传后立刻删** ——
     * `enqueueUpload` 是异步的（拷贝在 `viewModelScope.launch` 里做），
     * 调用方拿到返回时内容还没读完，此时删源文件会让它读到 0 字节。
     *
     * 所以改为在**下次打开应用选择器时**统一清理（见 [purgeStaleCopies]）。
     * 清理时会跳过"正在拷贝中"的那一个（由调用方传入）。代价是副本会多留
     * 一会儿 —— 但它在 cacheDir 下，系统空间紧张时会自行回收，不会无限增长。
     *
     * @return 复制后的文件；失败返回 null（调用方据此提示，不崩）
     */
    fun copyToCache(entry: AppEntry, targetDir: File): File? =
        runCatching {
            val src = File(entry.apkPath)
            if (!src.exists() || !src.isFile) return null

            targetDir.mkdirs()
            val out = targetFileFor(entry, targetDir)

            src.inputStream().use { ins ->
                out.outputStream().use { o -> ins.copyTo(o) }
            }

            // 拷完发现是空文件 / 中途失败留下的半截文件：一并删掉再返回 null，
            // 否则失败路径同样会留下垃圾（而且是个看起来"正常"的 .apk，
            // 用户和后续排查都会被它误导）。
            if (!out.exists() || out.length() <= 0L) {
                out.delete()
                return null
            }
            out
        }.getOrNull()

    /**
     * 清空 `uploaded_apps` 目录里上一轮遗留的副本。
     *
     * 在打开应用选择器时调用（见 `InstalledAppPickerDialog`）。这个时机
     * 相对安全：上一轮的拷贝通常已经结束，本次还没开始选。
     *
     * 为什么不在上传后立刻删：`enqueueUpload` 是异步的，调用方拿到返回时
     * 内容还没读完，删源文件会让它读到 0 字节（详见 [copyToCache] 注释）。
     *
     * 为什么必须有这个兜底：进程被杀死、上传任务根本没起来、用户选中后
     * 又反悔 —— 这些路径都不会有人来删副本，不清就会一直累积。
     *
     * ─────────────────────────────────────────────────────────────
     * ⚠️ [keep] 参数不是可选的优化，是**防竞态的必要条件**
     * ─────────────────────────────────────────────────────────────
     * 存在这样一条时序：用户选中 App → 上一轮的拷贝还在进行 → 用户立刻
     * 再打开选择器 → 这里的删除**正好把正在写的那份文件删掉** →
     * `copyToCache` 的 `out.length()` 读到 0（Linux 下已打开的文件被
     * unlink 后仍可写，但大小不再可信）→ 上传一个空/截断的 APK →
     * 服务端秒回包 → 用户看到"上传成功"但云端是个坏文件。
     *
     * 这正是本仓库反复栽过的"假成功"，绝不能在这里重演。
     * 因此调用方必须把"当前正在拷贝的文件"传进来，这里跳过它。
     *
     * @param keep 不能删的文件（正在拷贝中）；null 表示没有在途任务
     */
    fun purgeStaleCopies(targetDir: File, keep: File? = null) {
        runCatching {
            targetDir.listFiles()?.forEach { f ->
                // 用绝对路径比较：调用方持有的可能是同一个 File 的另一次构造，
                // File 的 equals 虽然按路径比较，但显式比绝对路径更明确、不受
                // 相对路径写法影响。
                if (keep != null && f.absolutePath == keep.absolutePath) return@forEach
                f.delete()
            }
        }
    }
}
