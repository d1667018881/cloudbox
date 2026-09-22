package com.cloudbox.app.feature.filelist

import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.cloudbox.app.common.FileIconResolver
import com.cloudbox.app.core.domain.model.CloudFile

/**
 * 文件 / 文件夹图标：优先用图标包里的 PNG（按后缀匹配），取不到则回退内置 Material 图标。
 *
 * PNG 用 [android.graphics.BitmapFactory] 直接解码本地文件 —— 不为一个图标
 * 引入图片加载库（Coil 等）。列表项数量有限，配合 remember 足够；
 * 切换图标包后路径变化会触发重新解码。
 */
@Composable
fun FileIcon(
    file: CloudFile,
    iconPackPath: String,
    modifier: Modifier = Modifier,
    tint: Color
) {
    val path = remember(iconPackPath, file.isFolder, file.name, file.fileType) {
        FileIconResolver.resolve(iconPackPath, file)
    }
    val bitmap = remember(path) {
        path?.let { p -> runCatching { android.graphics.BitmapFactory.decodeFile(p) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = null, modifier = modifier)
    } else {
        Icon(
            if (file.isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            null,
            modifier = modifier,
            tint = tint
        )
    }
}
