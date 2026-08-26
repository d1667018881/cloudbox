package com.cloudbox.app.feature.resolve

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 链接解析页：粘贴分享链接（支持多行批量）→ 直链解析 → 下载/复制/收藏。
 * 同时承载剪贴板监听弹窗的解析入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    onBack: () -> Unit,
    initialLink: String? = null,
    viewModel: ResolveViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(initialLink) {
        initialLink?.let {
            viewModel.onInputChange(it)
            viewModel.resolve()
        }
    }
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
                title = { Text("链接解析") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                label = { Text("粘贴分享链接（支持多行）") },
                placeholder = { Text("https://www.lanzou.com/iXXXXX") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text("提取码（可选）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = viewModel::resolve, enabled = !state.resolving) {
                    if (state.resolving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("解析")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("直链有效期约 2 小时且绑定 Referer；批量解析间隔 1-3s 防风控",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.weight(1f)) {
                items(state.results, key = { it.shareUrl }) { item ->
                    ResolveItemRow(item, viewModel)
                }
            }
        }
    }
}

@Composable
private fun ResolveItemRow(
    item: ResolveItem,
    viewModel: ResolveViewModel
) {
    // 在 composable 上下文中获取剪贴板实例（LocalClipboardManager 是 composable 属性）
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(item.shareUrl, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        val link = item.link
        when {
            link != null -> {
                Text(link.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(link.url, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Row {
                    IconButton(onClick = { viewModel.download(item) }) {
                        Icon(Icons.Filled.Download, "下载", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(link.url)) }) {
                        Icon(Icons.Filled.ContentCopy, "复制直链")
                    }
                    IconButton(onClick = { viewModel.favorite(item.shareUrl, link.fileName) }) {
                        Icon(Icons.Filled.Star, "收藏")
                    }
                }
            }
            item.error != null -> {
                Text("解析失败：${item.error}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
