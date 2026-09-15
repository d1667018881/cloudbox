package com.cloudbox.app.feature.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cloudbox.app.core.domain.model.FavoriteShare

/**
 * 收藏夹：收藏的分享链接列表（需求规格 6 节）。
 *
 * 本轮补齐（对齐原版 `favorites.lua`）：
 * - **编辑**：改名称与备注
 * - **置顶**：置顶项恒排列表最前
 * - **复制链接**：一键复制分享地址
 * - 删除、解析沿用原有能力
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenShare: (String) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val favorites by viewModel.favorites.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 正在编辑的收藏项（非空即弹编辑框）
    var editTarget by remember { mutableStateOf<FavoriteShare?>(null) }
    // 长按/更多菜单的锚点项
    var menuTarget by remember { mutableStateOf<FavoriteShare?>(null) }
    // 删除二次确认
    var deleteTarget by remember { mutableStateOf<FavoriteShare?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    editTarget?.let { fav ->
        EditFavoriteDialog(
            initial = fav,
            onDismiss = { editTarget = null },
            onConfirm = { name, remark ->
                viewModel.edit(fav.shareUrl, name, remark)
                editTarget = null
            }
        )
    }

    deleteTarget?.let { fav ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除收藏") },
            text = { Text("确定要从收藏夹移除「${fav.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(fav.shareUrl)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("收藏夹") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        if (favorites.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(120.dp))
                Text("暂无收藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    "在解析页把分享链接加入收藏后会出现在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(favorites, key = { it.shareUrl }) { fav ->
                    FavoriteRow(
                        fav = fav,
                        onOpen = { onOpenShare(fav.shareUrl) },
                        onMore = { menuTarget = fav }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // 更多操作菜单（长按或点右侧「⋯」触发）
    menuTarget?.let { fav ->
        Box(Modifier.fillMaxSize()) {
            DropdownMenu(expanded = true, onDismissRequest = { menuTarget = null }) {
                DropdownMenuItem(
                    text = { Text("编辑名称与备注") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { menuTarget = null; editTarget = fav }
                )
                DropdownMenuItem(
                    text = { Text(if (fav.pinned) "取消置顶" else "置顶") },
                    leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                    onClick = { menuTarget = null; viewModel.togglePin(fav) }
                )
                DropdownMenuItem(
                    text = { Text("复制链接") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = {
                        menuTarget = null
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("分享链接", fav.shareUrl))
                        viewModel.notify("已复制链接")
                    }
                )
                DropdownMenuItem(
                    text = { Text("去解析") },
                    onClick = { menuTarget = null; onOpenShare(fav.shareUrl) }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = { menuTarget = null; deleteTarget = fav }
                )
            }
        }
    }
}

/** 单条收藏：轻点解析，长按/点「⋯」出菜单 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRow(
    fav: FavoriteShare,
    onOpen: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onMore)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Star,
            null,
            tint = if (fav.pinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (fav.pinned) {
                    Icon(
                        Icons.Filled.PushPin, "已置顶",
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    fav.name.ifBlank { "未命名" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
            }
            Text(
                fav.shareUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (fav.remark.isNotBlank()) {
                Text(
                    "备注：${fav.remark}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
        TextButton(onClick = onOpen) { Text("解析") }
        IconButton(onClick = onMore) { Icon(Icons.Filled.Edit, "更多操作", Modifier.size(18.dp)) }
    }
}

/** 编辑收藏：名称 + 备注 */
@Composable
private fun EditFavoriteDialog(
    initial: FavoriteShare,
    onDismiss: () -> Unit,
    onConfirm: (name: String, remark: String) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var remark by remember { mutableStateOf(initial.remark) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑收藏") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text("备注") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, remark) },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
