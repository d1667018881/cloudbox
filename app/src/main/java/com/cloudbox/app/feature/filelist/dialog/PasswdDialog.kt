package com.cloudbox.app.feature.filelist.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 提取码对话框（V52，对齐蓝云「设置密码弹窗」home_file.lua:2840）。
 *
 * 交互三件套（蓝云原版语义）：
 * 1. 打开时读回当前码预填（[initialPwd]，来自 getFileShare 的 pwd+onof）
 * 2. 「启用提取码」开关：关 = 提交空码（API 层 shows=0 → 关闭提取码）
 * 3. 开 + 留空点确定 → 提示「请输入内容」，不提交
 *
 * 修复的两条真机反馈（TA 2026-10-09）：
 * - 「看不到本来设置的提取码是多少」→ 现在读回预填
 * - 「写着留空关闭但留空不能确认」→ 关闭语义改成开关（蓝云同款），
 *   标题不再误导；SimpleInputDialog 的 isNotBlank 挡空不再影响本弹窗
 */
@Composable
fun PasswdDialog(
    title: String,
    initialPwd: String,
    initialOn: Boolean,
    loading: Boolean = false,
    onConfirm: (enabled: Boolean, pwd: String) -> Unit,
    onDismiss: () -> Unit
) {
    var enable by remember { mutableStateOf(initialOn) }
    var code by remember { mutableStateOf(initialPwd) }
    var warned by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (loading) {
                    Text(
                        "读取当前提取码中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用提取码", Modifier.weight(1f))
                    Switch(checked = enable, onCheckedChange = { enable = it; warned = false })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; warned = false },
                    label = { Text("提取码（2-6 位）") },
                    singleLine = true,
                    enabled = enable,
                    modifier = Modifier.fillMaxWidth()
                )
                if (enable && code.isBlank() && warned) {
                    Text(
                        "请输入内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "关闭开关 = 取消提取码（对齐蓝云：关闭靠开关，不是留空）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (enable && code.isBlank()) {
                    warned = true
                } else {
                    onConfirm(enable, code.trim())
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
