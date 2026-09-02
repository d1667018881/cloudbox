package com.cloudbox.app.feature.filelist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.core.domain.model.ShareInfo
import com.cloudbox.app.feature.filelist.dialog.MoveFolderDialog
import com.cloudbox.app.feature.filelist.dialog.RenameDialog
import com.cloudbox.app.feature.filelist.dialog.ShareDialog
import com.cloudbox.app.feature.filelist.dialog.SimpleInputDialog

/**
 * 文件列表主界面：面包屑 + 双模式（列表/网格）+ 下拉刷新 + 分页 + 多选批量操作。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    onOpenSearch: () -> Unit,
    onOpenRecycle: () -> Unit,
    viewModel: FileListViewModel = hiltViewModel(),
    uploadViewModel: com.cloudbox.app.feature.upload.UploadViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val uploadState by uploadViewModel.uiState.collectAsState()
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CloudFile?>(null) }
    var moveTarget by remember { mutableStateOf(false) }
    var passwdTarget by remember { mutableStateOf<CloudFile?>(null) }
    var descTarget by remember { mutableStateOf<CloudFile?>(null) }
    var showFabMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // V5：+ FAB 直传当前目录（SAF 多选）——上传不再是独立 Tab
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 目标 = 当前浏览目录（栈顶），传完自动刷新列表
            uploadViewModel.enqueueUpload(uris, state.folderStack.last().first)
        }
    }

    // 上传会话结束（含部分失败）→ 刷新当前目录（V5：修复"上传成功但列表不更新"）
    LaunchedEffect(Unit) {
        uploadViewModel.uploadFinished.collect { viewModel.refresh() }
    }

    // 上传结果提示（消费即清，防止 Tab 切换重建 composition 时重放同一条）
    LaunchedEffect(uploadState.message) {
        uploadState.message?.let {
            snackbarHostState.showSnackbar(it)
            uploadViewModel.dismissMessage()
        }
    }

    // 系统返回键：多选/非根目录时先退多选/上级；根目录不拦截（放行系统默认退出）
    androidx.activity.compose.BackHandler(
        enabled = state.selectionMode || state.folderStack.size > 1
    ) { viewModel.back() }

    // 消费一次性提示
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (state.selectionMode) {
                            Text("已选 ${state.selected.size} 项")
                        } else {
                            // 面包屑导航
                            LazyRow(verticalAlignment = Alignment.CenterVertically) {
                                items(state.folderStack.size) { i ->
                                    val (_, name) = state.folderStack[i]
                                    TextButton(onClick = { viewModel.navigateTo(i) }) {
                                        Text(if (i == state.folderStack.lastIndex) name else "$name ›",
                                            style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        if (state.selectionMode) {
                            IconButton(onClick = viewModel::exitSelection) { Icon(Icons.Filled.Close, "取消选择") }
                        } else {
                            IconButton(onClick = { viewModel.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                        }
                    },
                    actions = {
                        if (!state.selectionMode) {
                            IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, "搜索") }
                            IconButton(onClick = viewModel::toggleGrid) {
                                Icon(if (state.gridMode) Icons.Filled.ViewList else Icons.Filled.GridView, "切换视图")
                            }
                        }
                    }
                )
                // 多选模式：批量操作栏
                if (state.selectionMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ActionChip("删除", Icons.Filled.Delete) { viewModel.deleteSelected() }
                        ActionChip("移动", Icons.Filled.DriveFileMove) { moveTarget = true }
                        ActionChip("分享", Icons.Filled.Share) {
                            state.files.firstOrNull { it.id == state.selected.firstOrNull() }?.let { viewModel.getShare(it) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                Box {
                    FloatingActionButton(onClick = { showFabMenu = true }) {
                        Icon(Icons.Filled.Add, "新建/上传")
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showFabMenu,
                        onDismissRequest = { showFabMenu = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("新建文件夹") },
                            leadingIcon = { Icon(Icons.Filled.Add, null) },
                            onClick = { showFabMenu = false; showNewFolder = true }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("上传文件到当前目录") },
                            leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                            onClick = {
                                showFabMenu = false
                                filePicker.launch(arrayOf("*/*"))
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
                if (state.files.isEmpty() && !state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.error ?: "目录为空",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (state.gridMode) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.files, key = { "${it.isFolder}_${it.id}" }) { file ->
                            GridItem(file, state, viewModel)
                        }
                        if (state.hasMore) {
                            item { LoadMoreButton(viewModel) }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.files, key = { "${it.isFolder}_${it.id}" }) { file ->
                            ListItem(file, state, viewModel)
                            HorizontalDivider()
                        }
                        if (state.hasMore) {
                            item { LoadMoreButton(viewModel) }
                        } else if (state.files.isNotEmpty()) {
                            item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Text("已全部加载", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                        }
                    }
                }
            }

            // V5：上传进度横幅（多批全局进度，底部悬浮）
            if (uploadState.uploading) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "上传中 ${uploadState.progress}/${uploadState.total}" +
                                        if (uploadState.currentFile.isNotBlank()) "：${uploadState.currentFile}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(6.dp))
                            val total = uploadState.total.coerceAtLeast(1)
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { uploadState.progress.toFloat() / total },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        SimpleInputDialog(
            title = "新建文件夹",
            placeholder = "文件夹名称",
            onDismiss = { showNewFolder = false },
            onConfirm = { name -> viewModel.createFolder(name); showNewFolder = false }
        )
    }
    renameTarget?.let { file ->
        RenameDialog(file = file, onDismiss = { renameTarget = null },
            onConfirm = { name -> viewModel.rename(file, name); renameTarget = null })
    }
    if (moveTarget) {
        MoveFolderDialog(
            onDismiss = { moveTarget = false },
            onConfirm = { folderId, _ -> viewModel.moveSelected(folderId); moveTarget = false }
        )
    }
    passwdTarget?.let { file ->
        SimpleInputDialog(
            title = "设置提取码（留空关闭）",
            placeholder = "2-6 位密码",
            onDismiss = { passwdTarget = null },
            onConfirm = { pwd -> viewModel.setPasswd(file, pwd); passwdTarget = null }
        )
    }
    descTarget?.let { file ->
        SimpleInputDialog(
            title = "设置描述（⚠️ 设置后不能清空）",
            placeholder = "描述内容",
            onDismiss = { descTarget = null },
            onConfirm = { desc -> viewModel.setDesc(file, desc); descTarget = null }
        )
    }
    state.shareResult?.let { share ->
        ShareDialog(share = share, onDismiss = viewModel::dismissShare)
    }
}

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = Modifier.height(36.dp)) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LoadMoreButton(viewModel: FileListViewModel) {
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        TextButton(onClick = viewModel::loadMore) { Text("加载更多") }
    }
}

/** 列表模式条目 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListItem(file: CloudFile, state: FileListUiState, viewModel: FileListViewModel) {
    val selected = file.id in state.selected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (state.selectionMode) viewModel.toggleSelect(file.id)
                    else if (file.isFolder) viewModel.enterFolder(file.id, file.name)
                    else viewModel.getShare(file)
                },
                onLongClick = { viewModel.enterSelection(file) }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (file.isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            null,
            tint = if (file.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (!file.isFolder && file.size != null) {
                Text("${file.size}  ·  ${file.time ?: ""}${if (file.onof == "1") "  ·  🔒" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, "已选", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 网格模式条目 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridItem(file: CloudFile, state: FileListUiState, viewModel: FileListViewModel) {
    val selected = file.id in state.selected
    Column(
        modifier = Modifier
            .padding(6.dp)
            .combinedClickable(
                onClick = {
                    if (state.selectionMode) viewModel.toggleSelect(file.id)
                    else if (file.isFolder) viewModel.enterFolder(file.id, file.name)
                    else viewModel.getShare(file)
                },
                onLongClick = { viewModel.enterSelection(file) }
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Icon(
                if (file.isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                null,
                modifier = Modifier.size(48.dp),
                tint = if (file.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selected) {
                Icon(Icons.Filled.Check, "已选", Modifier.size(20.dp).align(Alignment.TopEnd),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}
