package com.cloudbox.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 设置页（需求规格 10 节）：域名配置入口 / UA / 后缀伪装 / 第三方解析 /
 * 深色模式 / 多账号管理 / Cookie 导出恢复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDomainConfig: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var uaDialog by remember { mutableStateOf(false) }
    var resolverDialog by remember { mutableStateOf(false) }
    var uaInput by remember { mutableStateOf(state.userAgent) }
    var resolverInput by remember { mutableStateOf(state.thirdPartyResolver) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle("账号管理（多账号）")
            state.accounts.forEach { acc ->
                val isCurrent = acc.uid == state.currentUid
                Row(
                    Modifier.fillMaxWidth().clickable { if (!isCurrent) viewModel.switchAccount(acc.uid) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isCurrent, onClick = { if (!isCurrent) viewModel.switchAccount(acc.uid) })
                    Column(Modifier.weight(1f)) {
                        Text(acc.uid, style = MaterialTheme.typography.bodyLarge)
                        Text("最近活跃：${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(acc.lastActiveAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { viewModel.removeAccount(acc.uid) }) { Text("删除") }
                }
            }
            HorizontalDivider()

            SectionTitle("Cookie")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Button(onClick = viewModel::exportCookies, modifier = Modifier.weight(1f)) { Text("导出到剪贴板") }
                Spacer(Modifier.height(0.dp))
                Spacer(Modifier.padding(start = 8.dp))
                Button(onClick = viewModel::restoreCookiesFromClipboard, modifier = Modifier.weight(1f)) { Text("从剪贴板恢复") }
            }
            HorizontalDivider()

            SectionTitle("网络与解析")
            SettingRow("User-Agent（桌面 UA 伪装）", state.userAgent.take(30)) { uaDialog = true }
            SettingRow("第三方直链解析服务", state.thirdPartyResolver.ifEmpty { "未配置（使用内置解析）" }) { resolverDialog = true }
            SettingRow("域名配置", "远程/手动覆盖/连通性测试") { onOpenDomainConfig() }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("上传后缀伪装", style = MaterialTheme.typography.bodyLarge)
                    Text("exe/apk 等自动改名为 .zip 上传，下载时还原", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.suffixSpoof, onCheckedChange = viewModel::saveSuffixSpoof)
            }
            HorizontalDivider()

            SectionTitle("深色模式")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                    Row(Modifier.clickable { viewModel.saveDarkMode(mode) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.darkMode == mode, onClick = { viewModel.saveDarkMode(mode) })
                        Text(label)
                    }
                }
            }
            HorizontalDivider()

            Spacer(Modifier.height(24.dp))
            Text("云匣 v0.1.0 · 仅供个人学习使用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
        }
    }

    if (uaDialog) {
        AlertDialog(
            onDismissRequest = { uaDialog = false },
            title = { Text("自定义 User-Agent") },
            text = {
                Column {
                    Text("默认桌面 Chrome UA；手机 UA 会触发蓝奏云隐藏 APK 等下载入口。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uaInput,
                        onValueChange = { uaInput = it },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveUserAgent(uaInput)
                    uaDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { uaDialog = false }) { Text("取消") } }
        )
    }
    if (resolverDialog) {
        AlertDialog(
            onDismissRequest = { resolverDialog = false },
            title = { Text("第三方解析服务 URL") },
            text = {
                Column {
                    Text("留空使用内置解析；服务需接受 POST {url, pwd?} 并返回 {\"url\": 直链}。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = resolverInput,
                        onValueChange = { resolverInput = it },
                        placeholder = { Text("https://your-resolver.example.com/api") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveThirdPartyResolver(resolverInput)
                    resolverDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { resolverDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.Filled.Language, null, Modifier.height(16.dp))
    }
}
