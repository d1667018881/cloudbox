package com.cloudbox.app.feature.filelist.dialog

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cloudbox.app.common.QrCodeUtil
import com.cloudbox.app.core.domain.model.ShareInfo

/**
 * 分享结果对话框：短链 + 二维码（ZXing）+ 复制 + 收藏。
 */
@Composable
fun ShareDialog(
    share: ShareInfo,
    onDismiss: () -> Unit,
    onFavorite: (ShareInfo) -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var favorited by remember { mutableStateOf(false) }
    /** 保存二维码后的提示（成功/失败原因） */
    var savedTip by remember { mutableStateOf<String?>(null) }

    /**
     * 二维码内容：与原版「蓝云」一致 —— 分享链接**带上提取码**（`<url>?pass=<pwd>`）。
     * 旧实现只编码裸链接，带码分享扫码后还要手动输入提取码。
     * 无提取码时保持裸链接（与原版一致，不强加 ?pass=）。
     */
    val qrContent = if (share.onof == "1" && share.pwd.isNotBlank()) {
        "${share.shareUrl}?pass=${share.pwd}"
    } else {
        share.shareUrl
    }

    LaunchedEffect(qrContent) {
        qr = QrCodeUtil.generate(qrContent)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享链接") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(share.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Text(
                    share.shareUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (share.onof == "1") {
                    Spacer(Modifier.height(4.dp))
                    Text("提取码：${share.pwd}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                qr?.let { Image(it.asImageBitmap(), "二维码", Modifier.size(200.dp)) }
                Spacer(Modifier.height(8.dp))
                Row {
                    IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(
                        share.shareUrl + if (share.onof == "1") "\n提取码：${share.pwd}" else "")) }) {
                        Icon(Icons.Filled.ContentCopy, "复制")
                    }
                    // 保存二维码图片：原版分享页的二维码是可存下来的。
                    // 光能看不能存，等于只能让对面"对着屏幕拍"。
                    qr?.let { bmp ->
                        IconButton(onClick = {
                            savedTip = com.cloudbox.app.common.QrCodeUtil
                                .saveToGallery(context, bmp)
                        }) {
                            Icon(Icons.Filled.SaveAlt, "保存二维码")
                        }
                    }
                    IconButton(onClick = { favorited = true; onFavorite(share) }) {
                        Icon(Icons.Filled.Star, "收藏", tint = if (favorited) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (favorited) {
                    Text("已收藏", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                savedTip?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
