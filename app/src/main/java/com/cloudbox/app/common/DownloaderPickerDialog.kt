package com.cloudbox.app.common

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 第三方下载器按需选择弹窗（V53）。
 *
 * 触发场景：用户开了「第三方下载器」开关但没配包名，点下载时弹这个——
 * 列出设备上所有能接 ACTION_SEND 文本分享的应用（ADM/1DM/浏览器等），
 * 点选即存配置并继续刚才的下载，不用去设置页翻。
 *
 * 「用内置下载」= 本次不配置，继续走内置队列（VM 的 NOT_CONFIGURED
 * 分流会回落内置并附提示）。
 */
@Composable
fun DownloaderPickerDialog(
    onDismiss: () -> Unit,
    onSelected: (pack: String, activity: String) -> Unit
) {
    val context = LocalContext.current
    val candidates = remember {
        runCatching {
            val pm = context.packageManager
            val probe = Intent(Intent.ACTION_SEND).setType("text/plain")
            pm.queryIntentActivities(probe, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
        }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择第三方下载器") },
        text = {
            Column {
                Text(
                    "已开启第三方下载器但还没配置。从下面选一个（选完自动记住，" +
                        "以后下载直接交给它）：",
                    style = MaterialTheme.typography.bodySmall
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (candidates.isEmpty()) {
                        Text(
                            "没找到能接收链接分享的应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    candidates.forEach { ri ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelected(ri.activityInfo.packageName, ri.activityInfo.name)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    ri.loadLabel(context.packageManager).toString(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    ri.activityInfo.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("用内置下载") }
        }
    )
}
