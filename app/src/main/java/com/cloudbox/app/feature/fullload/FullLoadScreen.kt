package com.cloudbox.app.feature.fullload

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 查看全盘文件（原版「查看全盘文件」）。
 *
 * 独立成页（而非像原版那样原地替换首页列表）：CloudBox 的 [com.cloudbox.app.feature.filelist.FileListScreen]
 * 带分页/排序/多选状态，原地替换会互相污染；独立页也更好承载长耗时进度与「取消」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullLoadScreen(
    onBack: () -> Unit,
    viewModel: FullLoadViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("查看全盘文件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!state.hasStarted) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("风险提示", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• 此操作短时间内会大量请求服务器，可能导致账号异常，请谨慎使用\n" +
                            "• 显示网盘中所有文件夹中的文件\n" +
                            "• 加载时长由网盘中文件数量决定\n" +
                            "• 加载过程中可能出现卡顿，请尽量不要进行其他操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                        Text("继续")
                    }
                }
                return@Column
            }

            if (state.loading) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    LinearProgressIndicator(
                        progress = if (state.total > 0) state.done.toFloat() / state.total else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "正在加载「${state.current}」…（${state.done}/${state.total}）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = viewModel::cancel) { Text("取消") }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val suffix = if (state.failedFolders > 0) "（${state.failedFolders} 个文件夹加载失败）" else ""
                    Text(
                        "共 ${state.entries.size} 个文件$suffix",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::start) { Text("重新加载") }
                }
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.entries, key = { "${it.file.isFolder}_${it.file.id}" }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.file.name) },
                        supportingContent = {
                            val size = entry.file.size
                            Text(if (size.isNullOrBlank()) entry.folderName else "${entry.folderName} · $size")
                        },
                        leadingContent = {
                            Icon(
                                if (entry.file.isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                null
                            )
                        }
                    )
                }
            }
        }
    }
}
