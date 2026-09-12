package com.cloudbox.app.feature.upload

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cloudbox.app.core.domain.repository.UploadProbeResult

/**
 * 上传自检结果弹窗（设置页与网盘页共用，故放在这里而不是某个 Screen 内部）。
 *
 * 展示 HTTP 码 / 被测文件（名 + 大小）/ 请求地址 / 凭证状态 / **服务端原始回包**，
 * 并提供"复制"——排障时把这几行发出来，就能判断是没登录、参数不对、
 * 文件太大，还是端点失效。
 */
@Composable
fun UploadProbeDialog(
    result: UploadProbeResult,
    onDismiss: () -> Unit,
    /** 上传过程时间线（可选）。有它就能看出 Worker 到底跑没跑、"秒成功"卡在哪一段。 */
    timeline: List<String> = emptyList()
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上传自检结果") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("HTTP ${result.httpCode}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                if (result.fileName.isNotBlank()) {
                    Text(
                        "文件：${result.fileName}（${result.fileSize / 1024} KB）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // 后缀伪装改过名时必须显示：失败可能就出在这个名字上
                if (result.uploadAs.isNotBlank()) {
                    Text(
                        "实际上传为：${result.uploadAs}（后缀伪装已开启）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "目标目录 id：${result.targetFolderId}" +
                        if (result.targetFolderId == -1L) "（根目录）" else "",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                if (result.elapsedMs >= 0) {
                    // 耗时是判断"秒成功"性质的关键：几毫秒回包 = 请求没到服务端
                    val verdict = when {
                        result.elapsedMs < 100 -> "（过快：请求可能没真正发出去）"
                        else -> ""
                    }
                    Text(
                        "耗时：${result.elapsedMs} ms$verdict",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (verdict.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "请求地址：${result.requestUrl}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (result.hasCredential) "登录凭证：已检测到"
                    else "登录凭证：缺失 —— 请先退出重新登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.hasCredential) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(10.dp))
                Text("服务端原始回包：", style = MaterialTheme.typography.labelLarge)
                Text(result.rawBody, style = MaterialTheme.typography.bodySmall)
                if (timeline.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("上传过程时间线：", style = MaterialTheme.typography.labelLarge)
                    Text(
                        timeline.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "probe",
                        "HTTP ${result.httpCode}\n${result.requestUrl}\n"
                            + "credential=${result.hasCredential}\n"
                            + "folder_id=${result.targetFolderId}\n"
                            + "file=${result.fileName} (${result.fileSize / 1024} KB)\n"
                            + (if (result.uploadAs.isNotBlank()) "uploadAs=${result.uploadAs}\n" else "")
                            + "elapsed=${result.elapsedMs}ms\n"
                            + result.rawBody
                            + (if (timeline.isNotEmpty())
                                "\n\n--- 上传时间线 ---\n" + timeline.joinToString("\n")
                            else "")
                    )
                )
                onDismiss()
            }) { Text("复制") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
