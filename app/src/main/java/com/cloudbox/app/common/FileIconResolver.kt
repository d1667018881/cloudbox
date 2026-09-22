package com.cloudbox.app.common

import com.cloudbox.app.core.domain.model.CloudFile
import java.io.File

/**
 * 按图标包规则解析条目应使用的 PNG 图标路径。
 *
 * 规则对齐原版（view.lua:1643-1654 / home_func.lua:408-410）：
 * - 文件：`<后缀小写>.png`，不存在则 `file.png`
 * - 文件夹：`folder/folder.png`
 *
 * @return PNG 绝对路径；无图标包 / 文件缺失时返回 null（调用方回退内置图标）
 */
object FileIconResolver {

    fun resolve(packPath: String, file: CloudFile): String? {
        if (packPath.isBlank()) return null
        val base = File(packPath)
        if (!base.isDirectory) return null

        val candidate = if (file.isFolder) {
            File(base, "folder/folder.png")
        } else {
            val byExt = file.fileType?.let { File(base, "$it.png") }
            if (byExt != null && byExt.isFile) byExt else File(base, "file.png")
        }
        return candidate.takeIf { it.isFile }?.absolutePath
    }
}
