package com.cloudbox.app.feature.download

import android.app.DownloadManager
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cloudbox.app.core.domain.model.DownloadSortMode
import com.cloudbox.app.core.domain.model.DownloadTask

/**
 * 下载记录页：进度 / 删除 / 打开文件（APK 跳安装器）/ 排序 / 清空。
 *
 * 清空与会话内排序对齐原版 `download.lua`（原版下载页有「时间/名称/大小」排序
 * 与「清空列表」）。注意清空**不删本地已下载文件**，只清列表与系统任务。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val records by viewModel.displayRecords.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空下载列表") },
            text = { Text("将移除全部下载记录（正在下载的任务会被取消）。已下载到本地的文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    // 排序菜单
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, "排序")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            DownloadSortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (mode == sortMode) "● ${mode.label}" else mode.label
                                        )
                                    },
                                    onClick = {
                                        viewModel.setSortMode(mode)
                                        sortMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    // 清空（有记录时才可用）
                    IconButton(
                        onClick = { confirmClear = true },
                        enabled = records.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.DeleteSweep, "清空列表")
                    }
                }
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(120.dp))
                Text("暂无下载记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(records, key = { it.downloadId }) { task ->
                    DownloadItem(task, viewModel)
                }
            }
        }
    }
}

@Composable
private fun DownloadItem(task: DownloadTask, viewModel: DownloadViewModel) {
    val progress = if (task.bytesTotal > 0) {
        task.bytesDownloaded.toFloat() / task.bytesTotal
    } else 0f
    val finished = task.status == DownloadManager.STATUS_SUCCESSFUL
    val paused = task.paused || task.status == -1

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(task.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    "${viewModel.statusText(task.status)}   ${formatBytes(task.bytesDownloaded)}/${formatBytes(task.bytesTotal)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (finished) {
                // 打开文件（APK 跳安装器）
                IconButton(onClick = { viewModel.openTask(task) }) {
                    Text("打开", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
            } else if (paused) {
                IconButton(onClick = { viewModel.resume(task.downloadId) }) {
                    Text("继续", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
            } else {
                IconButton(onClick = { viewModel.pause(task.downloadId) }) {
                    Text("暂停", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
            IconButton(onClick = { viewModel.cancel(task.downloadId) }) {
                Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp))
            }
        }
        if (!finished) {
            LinearProgressIndicator(
                // material3 1.3.0（BOM 2024.09.03）签名是 progress: Float，
                // 1.7.0 才改成 () -> Float 的 lambda 版——本项目锁前者
                progress = progress,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
