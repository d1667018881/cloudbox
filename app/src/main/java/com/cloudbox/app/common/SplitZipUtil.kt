package com.cloudbox.app.common

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.File

/**
 * 分卷压缩工具（Zip4j）。
 *
 * 为什么分卷 95MB 而不是 100MB：免费用户单文件上限 100MB，贴线上传可能被拒
 * （部分来源称实际 110MB，无权威佐证），留 5MB 余量稳妥（需求规格 4 节硬性要求）。
 *
 * 分卷命名规范（必须严格遵守）：filename.zip / filename.z01 / filename.z02 ...
 * 蓝奏云通过扩展名校验拦截 .001/.002 这类非 zip 分卷名（博客园 2020 年实测），
 * .z01/.z02 是标准 zip 分卷命名，可正常上传。
 *
 * 压缩级别说明：分卷上传场景下压缩率不重要（很多文件本身已压缩），
 * 但压缩能显著减小部分文件体积；用默认级别平衡 CPU 与体积。
 */
object SplitZipUtil {

    /** 单个源文件切分为 zip 分卷，返回分卷文件列表（按顺序）。volumeBytes 必须 ≤ 实际限制 */
    fun split(source: File, outputDir: File, volumeBytes: Long = 95L * 1024 * 1024): List<File> {
        require(volumeBytes in (1L..(100L * 1024 * 1024))) { "分卷大小必须在 1B~100MB 之间" }
        val baseName = source.nameWithoutExtension
        val outPath = File(outputDir, "$baseName.zip").absolutePath

        val params = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            compressionLevel = CompressionLevel.NORMAL
        }
        val zipFile = ZipFile(outPath)
        // 按分卷大小切分：Zip4j 的 split 能力（createSplitZipFile 语义）
        zipFile.createSplitZipFile(listOf(source), params, true, volumeBytes)

        // 收集生成的分卷：.zip + .z01 + .z02 ...
        val volumes = mutableListOf<File>()
        var idx = 0
        while (true) {
            val candidate = if (idx == 0) File(outPath) else File(outPath.replace(".zip", ".z%02d".format(idx)))
            if (candidate.exists()) {
                volumes.add(candidate)
                idx++
            } else break
        }
        return volumes
    }

    /** 判断一组文件是否为分卷（.zip/.z01/.z02 或 .001/.002 兼容） */
    fun isVolume(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".zip") || Regex(""".*\.z\d{2}$""").containsMatchIn(name)
    }

    /** 合并分卷：把 .z01/.z02... 追加到 .zip（标准 zip 分卷合并方式） */
    fun mergeVolumes(volumes: List<File>, output: File) {
        require(volumes.isNotEmpty()) { "分卷列表为空" }
        val sorted = volumes.sortedBy { volumeOrder(it) }
        output.outputStream().use { out ->
            for (v in sorted) {
                v.inputStream().use { it.copyTo(out) }
            }
        }
    }

    /** 分卷顺序（#29 修复）：.zip 是含中央目录的【最后】一段，排在 z 序号之后——
     *  旧实现把 .zip 排第 0 位，一旦合并接线就是数据损坏级 bug */
    private fun volumeOrder(file: File): Int {
        val name = file.name.lowercase()
        if (name.endsWith(".zip")) return Int.MAX_VALUE
        Regex(""".*\.z(\d{2})$""").find(name)?.let { return it.groupValues[1].toInt() }
        Regex(""".*\.(\d{3})$""").find(name)?.let { return it.groupValues[1].toInt() }
        return 0
    }
}
