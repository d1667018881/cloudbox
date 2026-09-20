package com.cloudbox.app.feature.recycle

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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.cloudbox.app.core.domain.model.CloudFile

/**
 * 回收站：列出 + 恢复 / 彻底删除 / 恢复全部 / 清空。
 * （接口走 mydisk.php HTML + formhash，见 FileRepositoryImpl）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleScreen(
    onBack: () -> Unit,
    viewModel: RecycleViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    /** 清空回收站二次确认（不可逆） */
    var confirmClear by remember { mutableStateOf(false) }
    /** 单条「彻底删除」的确认目标 */
    var deleteTarget by remember { mutableStateOf<CloudFile?>(null) }

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
                title = { Text("回收站") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    TextButton(onClick = viewModel::restoreAll) { Text("恢复全部") }
                    TextButton(onClick = { confirmClear = true }) { Text("清空") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading && state.items.files.isEmpty() && state.items.folders.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items.folders + state.items.files, key = { "${it.isFolder}_${it.id}" }) { file ->
                        RecycleItemRow(file, viewModel, onRequestDelete = { deleteTarget = it })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // 查看回收站文件夹内容（只读）：对齐原版 recycle.lua 的「查看文件夹弹窗」
    state.folderDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = { viewModel.closeFolderDialog() },
            title = { Text(dialog.folderName, maxLines = 1) },
            text = {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    when {
                        dialog.loading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        dialog.error != null -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(dialog.error, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        dialog.files.isEmpty() -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("此文件夹内没有文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        else -> {
                            Column {
                                Text(
                                    "此文件夹内有 ${dialog.files.size} 个文件",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                LazyColumn {
                                    items(dialog.files, key = { it.id }) { f ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Filled.InsertDriveFile, null,
                                                Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.size(8.dp))
                                            Text(f.name, Modifier.weight(1f), maxLines = 1,
                                                style = MaterialTheme.typography.bodySmall)
                                            f.size?.let {
                                                Text(it, style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.closeFolderDialog() }) { Text("关闭") }
            }
        )
    }

    // 清空回收站：不可逆（原版 recycle.lua 也有「清空回收站弹窗」）。
    // 旧实现点「清空」直接删，误触一下就全没了。
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空回收站") },
            text = { Text("将永久删除回收站内的全部文件与文件夹，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    // 单条「彻底删除」同样不可逆，先确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("彻底删除") },
            text = { Text("将永久删除「${target.name}」，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    viewModel.deleteComplete(target)
                }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun RecycleItemRow(
    file: CloudFile,
    viewModel: RecycleViewModel,
    /** 「彻底删除」前先交给上层弹确认（不可逆操作不在行内直接执行） */
    onRequestDelete: (CloudFile) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (file.isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            null,
            tint = if (file.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(12.dp))
        Text(file.name, Modifier.weight(1f), maxLines = 1)
        // 文件夹：多一个「查看」入口（回收站里的文件夹是个黑盒）
        if (file.isFolder) {
            IconButton(onClick = { viewModel.openFolderDialog(file) }) {
                Icon(Icons.Filled.Visibility, "查看文件夹内容",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = { viewModel.restore(file) }) {
            Icon(Icons.Filled.Restore, "恢复", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { onRequestDelete(file) }) {
            Icon(Icons.Filled.DeleteForever, "彻底删除", tint = MaterialTheme.colorScheme.error)
        }
    }
}
