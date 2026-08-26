package com.cloudbox.app.feature.domain

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cloudbox.app.common.AppConstants

/**
 * 域名配置页：手动覆盖 + 远程 URL 拉取 + 并发连通性测试。
 * 为什么这个页面重要：蓝奏云域名漂移是常态（lanzou → lanzoux → lanzoui → lanzoup
 * → lanzouu → lanzouo → lanzouh…），域名打不开时用户在此一键换域或拉远程配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainConfigScreen(
    onBack: () -> Unit,
    viewModel: DomainConfigViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var remoteUrlInput by rememberSaveable { mutableStateOf(state.remoteUrl) }

    LaunchedEffect(state.remoteUrl) { remoteUrlInput = state.remoteUrl }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("域名配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("当前生效配置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            DomainFieldRow("登录入口", state.config.loginEntry, DomainField.LOGIN, viewModel)
            DomainFieldRow("管理主域", state.config.diskMain, DomainField.DISK, viewModel)
            DomainFieldRow("分享基址", state.config.shareBase, DomainField.SHARE, viewModel)
            DomainFieldRow("上传接口域", state.config.uploadServer, DomainField.UPLOAD, viewModel)

            Spacer(Modifier.height(16.dp))
            Text("备用域名池", style = MaterialTheme.typography.titleMedium)
            Text(
                state.config.fallbackDomains.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "注：lanzous.com 已被第三方抢注（解析到不良站点），本应用内置黑名单拦截，不会出现在此。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(Modifier.height(16.dp))
            Row {
                Button(onClick = viewModel::saveLocal, modifier = Modifier.weight(1f)) { Text("保存本地配置") }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = viewModel::resetLocal, modifier = Modifier.weight(1f)) { Text("重置默认") }
            }

            Spacer(Modifier.height(24.dp))
            Text("远程配置源（GitHub Gist / HTTPS JSON）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = remoteUrlInput,
                onValueChange = { remoteUrlInput = it; viewModel.updateRemoteUrl(it) },
                label = { Text("远程 JSON URL") },
                placeholder = { Text(AppConstants.DEFAULT_REMOTE_DOMAIN_URL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::fetchRemote,
                enabled = remoteUrlInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("拉取并应用") }

            Spacer(Modifier.height(24.dp))
            Text("连通性测试（并发 HEAD 测 RTT）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::testConnectivity, enabled = !state.testing) {
                if (state.testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("测试连通性")
                }
            }
            Spacer(Modifier.height(8.dp))
            state.latencies.forEach { latency ->
                val rtt = latency.rttMs?.let { "${it}ms" } ?: "失败"
                Text(
                    "${latency.url}  →  $rtt${latency.error?.let { "（${it}）" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            state.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun DomainFieldRow(
    label: String,
    value: String,
    field: DomainField,
    viewModel: DomainConfigViewModel
) {
    OutlinedTextField(
        value = value,
        onValueChange = { viewModel.updateField(field, it) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
}
