package com.cloudbox.app.feature.filelist.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 通用单输入框对话框：新建文件夹 / 提取码 / 描述 / 重命名。
 *
 * [initialValue] 用于「编辑已有内容」的场景（如改文件描述）——原版网页端
 * 打开描述弹窗时会先调 task=12 读回原值填进输入框，这里对应同一个需求。
 * 用 `remember(key)` 绑定：同一个弹窗换目标文件时能重新初始化，
 * 否则 Compose 会复用旧 state，把上一个文件的描述带过来。
 *
 * [singleLine] 描述类内容建议 false（可换行、看得全），短字段保持 true。
 */
@Composable
fun SimpleInputDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialValue: String = "",
    singleLine: Boolean = true,
    enabled: Boolean = true
) {
    // key 用 initialValue + title：换目标文件时重新初始化输入框
    var text by remember(title, initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("请输入内容", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(placeholder) },
                    singleLine = singleLine,
                    enabled = enabled,
                    minLines = if (singleLine) 1 else 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = enabled && text.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
