package com.cloudbox.app.feature.starred

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 星标文件夹页（对齐原版「星标文件夹」）。
 *
 * 与「收藏夹」的区别：这里聚合的是**自己网盘内的文件夹**（存 folderId），
 * 点进去直接浏览该目录；收藏夹存的是分享链接。
 *
 * 数据全在本地，进页面不发网络请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredFoldersScreen(
    onBack: () -> Unit,
    /** 点击星标项 → 打开对应目录（folderId, 显示名） */
    onOpenFolder: (Long, String) -> Unit,
    viewModel: StarredFoldersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

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
                title = { Text("星标文件夹") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::sortByName) {
                        Icon(Icons.AutoMirrored.Filled.Sort, "按名称排序")
                    }
                    IconButton(onClick = viewModel::showHelp) {
                        Icon(Icons.Filled.HelpOutline, "说明")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.items.isEmpty() -> Text(
                    "还没有星标的文件夹。\n\n在「网盘」里点文件夹右侧的“⋯”，选「添加到星标文件夹」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items, key = { it.folderId }) { item ->
                        StarredRow(
                            name = item.name,
                            remark = item.remark,
                            onClick = { onOpenFolder(item.folderId, item.name) },
                            onEdit = { viewModel.openEdit(item) },
                            onRemove = { viewModel.askRemove(item) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // ---- 编辑名称 / 备注 ----
    state.editTarget?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissEdit,
            title = { Text("编辑星标项") },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.editName,
                        onValueChange = viewModel::setEditName,
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = state.editRemark,
                        onValueChange = viewModel::setEditRemark,
                        label = { Text("备注（可选）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "这里改的只是本地记录的名称，不会改名网盘里的原文件夹。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::saveEdit) { Text("保存") } },
            dismissButton = { TextButton(onClick = viewModel::dismissEdit) { Text("取消") } }
        )
    }

    // ---- 取消星标确认 ----
    state.confirmRemove?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRemove,
            title = { Text("取消星标") },
            text = { Text("将「${target.name}」从星标列表移除？\n\n只影响这份本地列表，网盘里的文件夹本身不会被改动。") },
            confirmButton = { TextButton(onClick = viewModel::confirmRemove) { Text("移除") } },
            dismissButton = { TextButton(onClick = viewModel::dismissRemove) { Text("取消") } }
        )
    }

    // ---- 说明 ----
    if (state.showHelp) {
        AlertDialog(
            onDismissRequest = viewModel::dismissHelp,
            title = { Text("星标文件夹说明") },
            text = {
                Text(
                    "星标文件夹用来聚合常用的文件夹。\n\n" +
                        "• 星标时会保存当时的文件夹信息；之后原文件夹改名或变动，这里不会自动更新。\n" +
                        "• 删除已星标的文件夹后，这条记录仍会留着，需要自己在这里取消星标。\n" +
                        "• 不同账号的星标数据独立存放，退出登录会一并删除该账号的星标。\n" +
                        "• 点右上角排序图标可按名称排序，结果会被保存下来。"
                )
            },
            confirmButton = { TextButton(onClick = viewModel::dismissHelp) { Text("知道了") } }
        )
    }
}

@Composable
private fun StarredRow(
    name: String,
    remark: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (remark.isNotBlank()) {
                Text(
                    remark,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, "更多操作", Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("编辑名称与备注") },
                    onClick = { menuOpen = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text("取消星标") },
                    onClick = { menuOpen = false; onRemove() }
                )
            }
        }
    }
}
