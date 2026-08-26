package com.cloudbox.app.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.Checkbox

/**
 * 登录页：账号/密码输入、登录、Cookie 手动导入（剪贴板）、域名配置入口。
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onOpenDomainConfig: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current
    var showCookieImportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.alreadyLoggedIn) {
        if (state.alreadyLoggedIn) onLoginSuccess()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text("云匣", style = MaterialTheme.typography.headlineMedium)
            Text(
                "轻量网盘客户端",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = state.uid,
                onValueChange = viewModel::onUidChange,
                label = { Text("账号") },
                leadingIcon = { Icon(Icons.Filled.Person, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.pwd,
                onValueChange = viewModel::onPwdChange,
                label = { Text("密码") },
                leadingIcon = { Icon(Icons.Filled.Lock, null) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = state.rememberPwd,
                    onCheckedChange = viewModel::onRememberChange
                )
                Text(
                    "记住密码（用于自动续期）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))

            state.error?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = viewModel::login,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("登录")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showCookieImportDialog = true },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("从剪贴板导入 Cookie")
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenDomainConfig) {
                Icon(Icons.Filled.Language, null, Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("域名配置（域名漂移时可修改）")
            }
        }
    }

    if (showCookieImportDialog) {
        CookieImportDialog(
            onDismiss = { showCookieImportDialog = false },
            onImport = { text ->
                showCookieImportDialog = false
                viewModel.importCookie(text)
            }
        )
    }
}

/** Cookie 导入对话框：从剪贴板读取 phpdisk_info 字符串 */
@Composable
private fun CookieImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val clipText = remember { clipboard.getText()?.text ?: "" }
    var text by remember { mutableStateOf(clipText) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 Cookie") },
        text = {
            Column {
                Text("粘贴 phpdisk_info 字符串（可从浏览器开发者工具复制），账号名作为槽位标识。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Cookie 内容") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
