package com.cloudbox.app.common

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * V54（TA 真机实锤「取消也下了」）取消语义修正：
 * - onDismissRequest（返回键/点弹窗外）= 纯取消，**不触发任何下载**
 * - 「取消」按钮 = 纯取消
 * - 「用内置下载」= 仅本次走内置队列（显式动作）
 * - 点选应用 = 保存配置并把挂起的下载交给第三方
 */
@Composable
fun DownloaderPickerDialog(
    onCancel: () -> Unit,
    onUseBuiltIn: () -> Unit,
    onSelected: (pack: String, activity: String) -> Unit
) {
    val context = LocalContext.current
    // V55：默认只显示「像下载器」的应用（白名单+关键词），过滤掉微信/QQ 等
    // 不相关分享目标；「显示全部」兜底冷门下载器。过滤后为空自动回落全量。
    val allCandidates = remember { com.cloudbox.app.common.DownloaderCandidates.queryAll(context) }
    val filtered = remember(allCandidates) {
        allCandidates.filter { com.cloudbox.app.common.DownloaderCandidates.isDownloaderish(it, context) }
    }
    var showAll by remember { mutableStateOf(filtered.isEmpty()) }
    val candidates = if (showAll) allCandidates else filtered
    AlertDialog(
        onDismissRequest = onCancel,
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
                    // V55：兜底切换——用户的下载器不在过滤结果里时展开全量
                    if (filtered.isNotEmpty() && allCandidates.size > filtered.size) {
                        TextButton(
                            onClick = { showAll = !showAll },
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                if (showAll) "只显示疑似下载器" else "没找到？显示全部应用",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUseBuiltIn) { Text("用内置下载") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}
