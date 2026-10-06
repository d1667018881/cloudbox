package com.cloudbox.app.feature.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
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
 * 对齐原版 `favorites.lua`：
 * - **编辑**：改名称与备注
 * - **置顶**：置顶项恒排列表最前
 * - **复制链接**：一键复制分享地址
 * - 删除、解析沿用原有能力
 *
 * V30 新增（对齐 v1.3.4.9）：
 * - **检查收藏文件夹更新**：工具栏「刷新」按钮 + 进度显示
 *   （原版 favorites.lua:1206 更新按钮，tooltip「检查收藏文件夹更新」）
 * - **更新红点**：检测到变化的文件夹在标题后显示红点
 *   （原版 `open_link_history[i].update`）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenShare: (String) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    // V49 分组过滤：null=全部（含所有分组），""=未分组
    val favorites = when (state.currentFolder) {
        null -> state.favorites
        else -> state.favorites.filter { it.folder == state.currentFolder }
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 正在编辑的收藏项（非空即弹编辑框）
    var editTarget by remember { mutableStateOf<FavoriteShare?>(null) }
    // 长按/更多菜单的锚点项
    var menuTarget by remember { mutableStateOf<FavoriteShare?>(null) }
    // 删除二次确认
    var deleteTarget by remember { mutableStateOf<FavoriteShare?>(null) }
    // V49：移动到分组对话框 / 分组管理对话框
    var moveTarget by remember { mutableStateOf<FavoriteShare?>(null) }
    var manageFolders by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
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

    moveTarget?.let { fav ->
        MoveToFolderDialog(
            initialFolder = fav.folder,
            folders = state.folders,
            onDismiss = { moveTarget = null },
            onConfirm = { folder ->
                viewModel.move(fav.shareUrl, folder)
                moveTarget = null
            }
        )
    }

    if (manageFolders) {
        ManageFoldersDialog(
            folders = state.folders,
            onDismiss = { manageFolders = false },
            onRename = { old, new -> viewModel.renameFolder(old, new) },
            onDelete = { viewModel.dissolveFolder(it) }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("收藏夹") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    // 原版：检查收藏文件夹更新（favorites.lua:1206 更新按钮）
                    if (state.checking) {
                        // 进度：原版用 Ticker 每秒刷新「正在检查收藏文件夹更新」
                        Text(
                            "${state.checkDone}/${state.checkTotal}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(12.dp))
                    } else {
                        IconButton(onClick = { viewModel.checkFolderUpdates() }) {
                            Icon(Icons.Filled.Refresh, "检查收藏文件夹更新")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.favorites.isEmpty()) {
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
            Column(Modifier.fillMaxSize().padding(padding)) {
                // 检查进度条（原版「正在检查收藏文件夹更新」+ 批量进度条）
                if (state.checking) {
                    Text(
                        if (state.checkCurrent.isBlank()) {
                            "正在检查收藏文件夹更新…"
                        } else {
                            "正在检查：${state.checkCurrent}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    if (state.checkTotal > 0) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { state.checkDone.toFloat() / state.checkTotal },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
                // V49：分组过滤条（对齐原版 bookmark_folder 收藏夹分组）
                FolderFilterRow(
                    folders = state.folders,
                    current = state.currentFolder,
                    onSelect = { viewModel.selectFolder(it) },
                    onManage = { manageFolders = true }
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(favorites, key = { it.shareUrl }) { fav ->
                        FavoriteRow(
                            fav = fav,
                            onOpen = {
                                // 点开即清红点（原版 favorites.lua fn20：update = false）
                                viewModel.clearUpdateFlag(fav)
                                onOpenShare(fav.shareUrl)
                            },
                            onMore = { menuTarget = fav }
                        )
                        // V47（2026-10-04 TA 反馈）：菜单锚定在**本行**——原来
                        // 挂在全屏 Box 上，DropdownMenu 锚定父容器左上角，
                        // 长按后菜单飞到屏幕角落而非长按的条目处
                        if (menuTarget == fav) {
                            FavoriteMenu(fav, menuTarget, viewModel, context, onOpenShare,
                                onEdit = { editTarget = fav },
                                onMove = { moveTarget = fav },
                                onDismiss = { menuTarget = null })
                        }
                        HorizontalDivider()
                    }
                }
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
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                // 更新红点（原版 open_link_history[i].update）
                if (fav.hasUpdate) {
                    Spacer(Modifier.size(6.dp))
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                }
                if (fav.isFolder) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "文件夹",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                fav.shareUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (fav.remark.isNotBlank()) {
                Text(
                    "备注：${fav.remark}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
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

/** 收藏条目的长按/更多菜单（锚定在本行） */
@Composable
private fun FavoriteMenu(
    fav: FavoriteShare,
    menuTarget: FavoriteShare?,
    viewModel: FavoritesViewModel,
    context: android.content.Context,
    onOpenShare: (String) -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit = {},
    onDismiss: () -> Unit
) {
    androidx.compose.material3.DropdownMenu(
        expanded = menuTarget == fav,
        onDismissRequest = onDismiss,
        // V48：右移 72dp（菜单宽约 200dp，右移后视觉中心落在条目中部，
        // 而不是紧贴屏幕左缘——TA 期望“在按的文件附近”）
        offset = androidx.compose.ui.unit.DpOffset(72.dp, 0.dp)
    ) {
        DropdownMenuItem(
            text = { Text("编辑名称与备注") },
            leadingIcon = { Icon(Icons.Filled.Edit, null) },
            onClick = { onDismiss(); onEdit() }
        )
        DropdownMenuItem(
            text = { Text(if (fav.folder.isBlank()) "移动到分组" else "移动分组（当前：${fav.folder}）") },
            leadingIcon = { Icon(Icons.Filled.Folder, null) },
            onClick = { onDismiss(); onMove() }
        )
        DropdownMenuItem(
            text = { Text(if (fav.pinned) "取消置顶" else "置顶") },
            leadingIcon = { Icon(Icons.Filled.PushPin, null) },
            onClick = { onDismiss(); viewModel.togglePin(fav) }
        )
        DropdownMenuItem(
            text = { Text("复制链接") },
            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
            onClick = {
                onDismiss()
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("分享链接", fav.shareUrl))
                viewModel.notify("已复制链接")
            }
        )
        // 原版只给文件夹型收藏提供「更新」入口（favorites.lua:1126）
        if (fav.isFolder) {
            DropdownMenuItem(
                text = { Text("检查此文件夹更新") },
                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                onClick = { onDismiss(); viewModel.checkFolderUpdates() }
            )
        }
        DropdownMenuItem(
            text = { Text("去解析") },
            // V47（TA 反馈）：其他项都有图标，唯独这项没有——看着别扭
            leadingIcon = { Icon(Icons.Filled.Bolt, null) },
            onClick = { onDismiss(); onOpenShare(fav.shareUrl) }
        )
        DropdownMenuItem(
            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); viewModel.remove(fav.shareUrl) }
        )
    }
}


// ==================== V49：收藏夹分组 UI（对齐原版 bookmark_folder） ====================

/**
 * 分组过滤条：全部 | 未分组 | 各分组 ⋯管理。
 * 用 FilterChip 而不是 Tab：分组可增删，Chip 行语义更直（原版同款横排）。
 */
@Composable
private fun FolderFilterRow(
    folders: List<String>,
    current: String?,
    onSelect: (String?) -> Unit,
    onManage: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = current == null,
            onClick = { onSelect(null) },
            label = { Text("全部") }
        )
        FilterChip(
            selected = current == "",
            onClick = { onSelect("") },
            label = { Text("未分组") }
        )
        folders.forEach { f ->
            FilterChip(
                selected = current == f,
                onClick = { onSelect(f) },
                label = { Text(f) }
            )
        }
        AssistChip(
            onClick = onManage,
            label = { Text("管理分组") }
        )
    }
}

/** 移动到分组：现有分组单选 + 新建分组输入 */
@Composable
private fun MoveToFolderDialog(
    initialFolder: String,
    folders: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到分组") },
        text = {
            Column {
                if (folders.isEmpty()) {
                    Text(
                        "还没有分组，输入新分组名创建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                folders.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(f) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Folder, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(f, Modifier.weight(1f))
                        if (f == initialFolder) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                if (initialFolder.isNotBlank()) {
                    Text(
                        "移出到未分组",
                        Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm("") }
                            .padding(vertical = 10.dp)
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("新分组名") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (newName.isNotBlank()) onConfirm(newName.trim()) },
                enabled = newName.isNotBlank()
            ) { Text("新建并移入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 分组管理：重命名 / 删除（删除=组内条目移回未分组，对话框里写清楚） */
@Composable
private fun ManageFoldersDialog(
    folders: List<String>,
    onDismiss: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理分组") },
        text = {
            Column {
                if (folders.isEmpty()) {
                    Text(
                        "暂无分组。长按收藏 → 移动到分组即可创建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                folders.forEach { f ->
                    if (renaming == f) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            label = { Text("重命名「$f」") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row {
                            TextButton(onClick = {
                                if (newName.isNotBlank()) onRename(f, newName.trim())
                                renaming = null
                            }) { Text("保存") }
                            TextButton(onClick = { renaming = null }) { Text("取消") }
                        }
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Folder, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(10.dp))
                            Text(f, Modifier.weight(1f))
                            TextButton(onClick = { renaming = f; newName = f }) { Text("重命名") }
                            TextButton(onClick = { onDelete(f) }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
