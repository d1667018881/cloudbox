package com.cloudbox.app.feature.filelist

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.feature.filelist.dialog.MoveFolderDialog
import com.cloudbox.app.feature.filelist.dialog.RenameDialog
import com.cloudbox.app.feature.filelist.dialog.ShareDialog
import com.cloudbox.app.feature.filelist.dialog.SimpleInputDialog
import kotlinx.coroutines.launch

/**
 * 文件列表主界面：面包屑 + 双模式（列表/网格）+ 下拉刷新 + 分页 + 多选批量操作。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    onOpenSearch: () -> Unit,
    onOpenRecycle: () -> Unit,
    /** 从其他 App 分享进来的文件/链接（未消费时非空） */
    pendingShare: com.cloudbox.app.common.ShareIntentHandler.SharedContent? = null,
    /** 分享内容已被消费，通知上层清空，避免旋转屏幕/重组时重复触发 */
    onShareConsumed: () -> Unit = {},
    /** 分享进来的链接交由此回调处理（跳到解析页） */
    onOpenSharedLink: (String) -> Unit = {},
    viewModel: FileListViewModel = hiltViewModel(),
    uploadViewModel: com.cloudbox.app.feature.upload.UploadViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    // V30：文件类型标签开关（对齐原版 show_file_type_label，默认开）
    val showFileTypeLabel by viewModel.settingsStore.showFileTypeLabel
        .collectAsState(initial = true)
    // V31：自动加载剩余内容（对齐原版 auto_load「自动加载页面剩余内容」）
    val autoLoad by viewModel.settingsStore.autoLoad.collectAsState(initial = true)
    val uploadState by uploadViewModel.uiState.collectAsState()
    val uploadTimeline by uploadViewModel.timeline.collectAsState()
    // 「从已安装应用上传」拷 APK 时要挂协程（几十 MB，不能占主线程）
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CloudFile?>(null) }
    var moveTarget by remember { mutableStateOf(false) }
    var passwdTarget by remember { mutableStateOf<CloudFile?>(null) }
    var showFabMenu by remember { mutableStateOf(false) }
    // V32：从已安装应用上传的选择器
    var showAppPicker by remember { mutableStateOf(false) }
    // 防重入：APK 拷贝期间用户再点一次会双会话并行
    var copyingApk by remember { mutableStateOf(false) }
    // "上传到指定目录"：先选目录，选完再拉起文件选择器
    var showUploadFolderPicker by remember { mutableStateOf(false) }
    // 上一轮"上传到指定目录"选中的目录（null = 用当前浏览目录）
    var pendingUploadFolderId by remember { mutableStateOf<Long?>(null) }
    // 批量操作对话框：设置提取码 / 修改资料
    var showBatchPwd by remember { mutableStateOf(false) }
    var showBatchDesc by remember { mutableStateOf(false) }
    // 删除二次确认（删除进回收站，但仍是破坏性操作，必须先确认）
    var showDeleteConfirm by remember { mutableStateOf(false) }
    /** 点击文件后弹出的操作菜单目标（null = 未打开） */
    var menuFile by remember { mutableStateOf<CloudFile?>(null) }
    /** 单文件删除确认目标 */
    var deleteTarget by remember { mutableStateOf<CloudFile?>(null) }
    /** 单文件移动到目标目录（true = 打开目录选择框） */
    var moveSingleTarget by remember { mutableStateOf<CloudFile?>(null) }
    // 排序菜单
    var showSortMenu by remember { mutableStateOf(false) }
    // 上传失败详情弹窗开关，以及**详情内容的快照**。
    // 必须声明在 LaunchedEffect 之前——Snackbar 动作要置位它们，
    // 而 Compose 的 remember 在同一作用域内需先声明后使用。
    var showFailureDetail by remember { mutableStateOf(false) }
    var failureDetailText by remember { mutableStateOf("") }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    // 在 @Composable 作用域取 context：上传失败点"网页上传"时要用它启动 Activity
    val context = androidx.compose.ui.platform.LocalContext.current

    // V5：+ FAB 直传当前目录（SAF 多选）——上传不再是独立 Tab
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 目标 = pendingUploadFolderId（"上传到指定目录"选的）?? 当前浏览目录（栈顶）
            // 用完后清空，避免下一次普通上传还传去上次选的那个目录
            val target = pendingUploadFolderId ?: state.folderStack.last().first
            pendingUploadFolderId = null
            uploadViewModel.enqueueUpload(uris, target)
        }
    }

    val webUploadLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        // 网页里传完后返回，刷新当前目录
        viewModel.refresh()
    }

    // 上传会话结束（含部分失败）→ 刷新当前目录（V5：修复"上传成功但列表不更新"）
    LaunchedEffect(Unit) {
        uploadViewModel.uploadFinished.collect { viewModel.refresh() }
    }

    // ==================== 接收「从其他 App 分享进来」的内容 ====================
    //
    // 两种形态分开处理：
    // - 文件：直接入队上传到**当前浏览目录**（分享过来时用户已经在某目录里，
    //   默认传到这里最符合直觉；想换目录可以退出到目标目录再分享一次）。
    // - 链接：跳到解析页（复用既有解析逻辑，含提取码自动填充）。
    //
    // 消费完立刻 onShareConsumed() 清空，否则 Compose 重组/旋转屏幕会把
    // 同一份内容重复上传 —— 这是分享接收最常见的坑。
    LaunchedEffect(pendingShare) {
        val share = pendingShare ?: return@LaunchedEffect
        if (share.isEmpty) {
            onShareConsumed()
            return@LaunchedEffect
        }
        if (share.hasFiles) {
            uploadViewModel.enqueueUpload(share.fileUris, state.folderStack.last().first)
        } else {
            share.text?.let { onOpenSharedLink(it) }
        }
        onShareConsumed()
    }

    // 上传结果提示（消费即清，防止 Tab 切换重建 composition 时重放同一条）。
    //
    // 失败时：停留更久，动作改成「看详情」——用户第一需求是知道**为什么**失败。
    // 详情弹窗里有完整时间线（任务是否入队、Worker 是否真的跑起来、服务端回包），
    // 这才是能定性问题的东西。网页上传仍保留在 FAB 菜单里作备用入口。
    LaunchedEffect(uploadState.message) {
        uploadState.message?.let { msg ->
            val res = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = if (uploadState.hasFailure) "看详情" else null,
                withDismissAction = uploadState.hasFailure,
                duration = if (uploadState.hasFailure)
                    androidx.compose.material3.SnackbarDuration.Long
                else
                    androidx.compose.material3.SnackbarDuration.Short
            )
            // ⚠️ 必须先把详情内容**快照**下来，再清 message。
            //
            //    旧写法是 `showFailureDetail = true` 后立刻 dismissMessage()，
            //    而弹窗渲染条件里带着 `uploadState.message != null` ——
            //    message 在同一帧被清空，条件永远不成立，于是**点了"看详情"什么都不弹**
            //    （用户实测反馈）。改为把当时的 message/失败名单复制进本地 state，
            //    弹窗只依赖这份快照，与 ViewModel 的清理时机彻底解耦。
            if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                failureDetailText = buildString {
                    append(msg).append("\n")
                    if (uploadState.failedFiles.isNotEmpty()) {
                        append("\n失败文件：\n")
                        uploadState.failedFiles.take(20)
                            .forEach { append("  · ").append(it).append("\n") }
                    }
                }
                showFailureDetail = true
            }
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
                            // 排序：当前目录内客户端排序（不动服务端数据）
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, "排序：${state.sortMode.label}")
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    com.cloudbox.app.feature.filelist.SortMode.entries.forEach { mode ->
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text(mode.label) },
                                            onClick = {
                                                showSortMenu = false
                                                viewModel.setSortMode(mode)
                                            },
                                            trailingIcon = {
                                                if (state.sortMode == mode) {
                                                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                                                }
                                            }
                                        )
                                    }
                                }
                            }
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
                        // 批量操作栏（两行，避免窄屏挤成一条滚动条）
                        //
                        // ⚠️ 这些按钮全部作用于**整个选中集合**。曾经这里有个
                        //    "分享"按钮写的是 selected.firstOrNull()，多选时只处理
                        //    第一条 —— 名不副实的假功能，已改为真正的批量分享。
                        ActionChip("全选", Icons.Filled.Check) { viewModel.selectAll() }
                        ActionChip("取消全选", Icons.Filled.Close) { viewModel.clearSelection() }
                        ActionChip("删除", Icons.Filled.Delete) { showDeleteConfirm = true }
                        ActionChip("移动", Icons.Filled.DriveFileMove) { moveTarget = true }
                        ActionChip("分享", Icons.Filled.Share) {
                            // 逐个取链接后拼接，一次性复制到剪贴板
                            viewModel.shareSelected { text ->
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("分享链接", text))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ActionChip("批量下载", Icons.Filled.Download) { viewModel.downloadSelected() }
                        ActionChip("设提取码", Icons.Filled.Lock) { showBatchPwd = true }
                        ActionChip("改资料", Icons.Filled.Edit) { showBatchDesc = true }
                    }
                    // 批量任务进度（串行执行，可能十几秒；不显示进度用户会以为卡死）
                    state.batchProgress?.let { p ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(p, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                Box {
                    // LocalContext.current 必须在 @Composable 作用域里取值；
                    // 放进 onClick（非 Composable lambda）会报
                    // "@Composable invocations can only happen from the context of a @Composable function"
                    val context = androidx.compose.ui.platform.LocalContext.current
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
                        // 上传**永远**走 App 原生直传：选完文件立刻自己传，不弹网页。
                        // 原版 App 就是这么做的（disasm/home.txt:6537-6560）。
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("上传文件到当前目录") },
                            leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                            onClick = {
                                showFabMenu = false
                                filePicker.launch(arrayOf("*/*"))
                            }
                        )
                        // V32：「从已安装应用上传」——选一个手机上已装好的 App，
                        // 直接把它的 APK 传上去，省掉"先导出安装包再选文件"那一步。
                        // 对齐原版「本机应用」目录（file.lua 的 获取应用线程）。
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("上传已安装应用…") },
                            leadingIcon = { Icon(Icons.Filled.Android, null) },
                            onClick = {
                                showFabMenu = false
                                showAppPicker = true
                            }
                        )
                        // 传去**别的**目录：先选目标目录，再选文件。
                        // 补的是"分享进来默认传当前目录、但我想传去别处"这个缺口
                        // —— 分享接收是外部发起的，用户来不及先切目录。
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("上传到指定目录…") },
                            leadingIcon = { Icon(Icons.Filled.Folder, null) },
                            onClick = {
                                showFabMenu = false
                                showUploadFolderPicker = true
                            }
                        )
                        // 官方网页上传页：仅作**手动备用入口**（传超大文件、或哪天
                        // 原生通道被风控挡住时用）。它不是默认路径。
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("打开官方网页上传页（备用）") },
                            leadingIcon = { Icon(Icons.Filled.Language, null) },
                            onClick = {
                                showFabMenu = false
                                webUploadLauncher.launch(
                                    android.content.Intent(
                                        context,
                                        com.cloudbox.app.feature.upload.WebViewUploadActivity::class.java
                                    ).putExtra(
                                        com.cloudbox.app.feature.upload.WebViewUploadActivity.EXTRA_FOLDER_ID,
                                        state.folderStack.last().first
                                    )
                                )
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
                        items(state.displayFiles, key = { "${it.isFolder}_${it.id}" }) { file ->
                            GridItem(file, state, viewModel, showFileTypeLabel) { menuFile = it }
                        }
                        // V31：列表加载到底时自动请求下一页（对齐原版「自动加载页面剩余内容」）
                        if (state.hasMore) {
                            item { AutoLoadMoreRow(viewModel, state, autoLoad) }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.displayFiles, key = { "${it.isFolder}_${it.id}" }) { file ->
                            ListItem(file, state, viewModel, showFileTypeLabel) { menuFile = it }
                            HorizontalDivider()
                        }
                        // V31：同上。原版没有"加载更多"按钮，滚动到底自动续拉；
                        // "已全部加载"的文案也一并去掉 —— 用户不需要被告知"没有了"。
                        if (state.hasMore) {
                            item { AutoLoadMoreRow(viewModel, state, autoLoad) }
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
                                // material3 1.3.0（BOM 2024.09.03）签名是 progress: Float，
                                // 1.7.0 才改成 () -> Float 的 lambda 版——本项目锁前者
                                progress = uploadState.progress.toFloat() / total,
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

    // "上传到指定目录"：选完目录后立刻拉起文件选择器。
    //
    // ⚠️ 目标目录必须用 remember 暂存：目录对话框关闭 → 系统文件选择器接管，
    //    期间 Compose 可能重组，局部变量会丢。存成受 remember 保护的状态才不会丢。
    if (showUploadFolderPicker) {
        MoveFolderDialog(
            title = "上传到",
            rootLabel = "根目录（我的文件）",
            onDismiss = { showUploadFolderPicker = false },
            onConfirm = { folderId, _ ->
                showUploadFolderPicker = false
                pendingUploadFolderId = folderId
                filePicker.launch(arrayOf("*/*"))
            }
        )
    }

    // ==================== 从已安装应用上传（V32） ====================
    //
    // 选中的应用 → 复制其主 APK 到缓存（同时改名为「应用名_包名尾.apk」）→
    // 交给 enqueueUpload 走完全相同的既有链路（分批 → Worker）。
    //
    // 为什么复用 enqueueUpload 而不是另开一条上传路径：
    // 那条链路已经处理了防重入、分批、离线等待、进度回放、失败名单等全部边界，
    // 重新实现一遍只会引入新的时序 bug。APK 就是普通文件，没有特殊之处。
    //
    // 复制那一步的必要性（不改名会传出一堆 base.apk）见 InstalledApps.copyToCache。
    if (showAppPicker) {
        com.cloudbox.app.feature.upload.InstalledAppPickerDialog(
            onDismiss = { showAppPicker = false },
            onPick = { app ->
                if (!copyingApk) {
                    copyingApk = true
                    // 拷 APK 可能几十 MB，必须在 IO 线程
                    scope.launch {
                        val dir = java.io.File(context.cacheDir, "uploaded_apps")
                        val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.cloudbox.app.common.InstalledApps.copyToCache(app, dir)
                        }
                        copyingApk = false
                        showAppPicker = false
                        if (copied == null) {
                            uploadViewModel.showMessage(
                                "读取不到「${app.label}」的安装包（可能被系统限制），请改用「上传文件到当前目录」"
                            )
                        } else {
                            // 目标目录与「上传文件到当前目录」一致：当前浏览目录
                            uploadViewModel.enqueueUpload(
                                listOf(android.net.Uri.fromFile(copied)),
                                state.folderStack.last().first
                            )
                        }
                    }
                }
            }
        )
    }

    // ==================== 单条操作菜单 ====================
    //
    // 点文件不再直接弹分享框，而是先给菜单 —— 对齐原版行为。
    //
    // 按文件夹 / 文件分流，对齐官网菜单（2026-09 实测）：
    // · 文件 ⋯ 菜单：外链分享 / 自定义外链 / 缩略图 / 重命名 / 移动 / 提取码 / 描述 / 短网址 / 直链
    // · 文件夹 ⋯ 菜单：外链分享 / 自定义外链 / 提取码 / 修改资料 / 短网址
    // 关键差异：文件夹**没有**「移动」和「重命名」，提取码走 task=16（不是 task=23）。
    menuFile?.let { file ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuFile = null },
            title = { Text(file.name, maxLines = 2) },
            text = {
                Column {
                    MenuAction("查看分享链接与提取码") {
                        menuFile = null
                        viewModel.getShare(file)
                    }
                    if (!file.isFolder) {
                        MenuAction("下载到本地") {
                            menuFile = null
                            viewModel.downloadSingle(file)
                        }
                        // 官网文件夹菜单无「重命名」（fol_ename 未定义）
                        MenuAction("重命名") {
                            menuFile = null
                            renameTarget = file
                        }
                    }
                    MenuAction(if (file.isFolder) "设置文件夹提取码" else "设置提取码") {
                        menuFile = null
                        passwdTarget = file
                    }
                    MenuAction("修改资料（描述）") {
                        menuFile = null
                        // 先读回原描述再打开弹窗（对齐原版 f_des → task=12 → f_desgo）
                        viewModel.loadDescForEdit(file)
                    }
                    // 官网文件夹菜单无「移动」（已实测确认）
                    if (!file.isFolder) {
                        MenuAction("移动到…") {
                            menuFile = null
                            moveSingleTarget = file
                        }
                    }
                    MenuAction("复制文件名") {
                        menuFile = null
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("文件名", file.name))
                        viewModel.showMessage("已复制文件名")
                    }
                    MenuAction("删除", danger = true) {
                        menuFile = null
                        deleteTarget = file
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuFile = null }) { Text("关闭") }
            }
        )
    }

    // 单文件删除确认
    deleteTarget?.let { file ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除") },
            text = { Text("将删除「${file.name}」。删除的内容会进入回收站，可在回收站恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSingle(file)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    // 单文件移动：选目标目录
    moveSingleTarget?.let { file ->
        MoveFolderDialog(
            title = "移动「${file.name}」到",
            onDismiss = { moveSingleTarget = null },
            onConfirm = { folderId, _ ->
                viewModel.moveSingle(file, folderId)
                moveSingleTarget = null
            }
        )
    }

    if (showDeleteConfirm) {
        // 统计选中项构成，把"要删什么"说清楚 —— 原版删除前也会提示条目数。
        val selectedFiles = state.files.filter { it.id in state.selected }
        val folderCount = selectedFiles.count { it.isFolder }
        val fileCount = selectedFiles.size - folderCount
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = {
                Text(
                    buildString {
                        append("将删除 ")
                        if (folderCount > 0) append("$folderCount 个文件夹")
                        if (folderCount > 0 && fileCount > 0) append("、")
                        if (fileCount > 0) append("$fileCount 个文件")
                        if (selectedFiles.isEmpty()) append("0 项")
                        append("。\n\n删除的内容会进入回收站，可在回收站恢复。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteSelected()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
    if (showBatchPwd) {
        SimpleInputDialog(
            title = "批量设置提取码（留空关闭）",
            // 文件夹也支持提取码（官网 task=16），不再是"会被跳过"
            placeholder = "2-6 位密码；文件和文件夹均生效",
            onDismiss = { showBatchPwd = false },
            onConfirm = { pwd -> viewModel.setPasswdSelected(pwd); showBatchPwd = false }
        )
    }
    if (showBatchDesc) {
        SimpleInputDialog(
            title = "批量修改资料",
            // 文件夹也能改资料（官网 fol_desgo → task=4），提示语不再说"会被跳过"
            placeholder = "描述内容；文件和文件夹均生效",
            onDismiss = { showBatchDesc = false },
            onConfirm = { desc -> viewModel.setDescSelected(desc); showBatchDesc = false }
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
    state.descTarget?.let { file ->
        SimpleInputDialog(
            title = when {
                state.descLoading -> "修改资料（读取中…）"
                // 文件夹的描述可随时改（task=4 整体覆盖），没有"不能清空"的限制
                file.isFolder -> "修改文件夹资料（话说）"
                else -> "修改资料（⚠️ 设置后不能清空）"
            },
            placeholder = "描述内容",
            initialValue = state.descDraft,
            singleLine = false,
            enabled = !state.descLoading,
            onDismiss = viewModel::dismissDescEdit,
            onConfirm = { desc ->
                viewModel.setDesc(file, desc)
                viewModel.dismissDescEdit()
            }
        )
    }
    state.shareResult?.let { share ->
        ShareDialog(share = share, onDismiss = viewModel::dismissShare)
    }

    // 上传失败时：用同一个弹窗把"时间线 + 失败名单"摊开。
    // 用户看到"上传失败"时最需要知道的是"到底哪一步断了"，而不是再猜一次。
    //
    // 条件只依赖本地的 showFailureDetail + failureDetailText 快照，
    // 不再检查 uploadState.message —— 那正是上一版"点看详情没反应"的原因
    // （Snackbar 回调里紧接着 dismissMessage()，两者在同一帧生效，
    //   message 已为 null，条件不成立，弹窗不渲染）。
    if (showFailureDetail && failureDetailText.isNotBlank()) {
        val detail = com.cloudbox.app.core.domain.repository.UploadProbeResult(
            httpCode = -2,
            requestUrl = "上传失败明细（非探针）",
            rawBody = failureDetailText,
            hasCredential = true
        )
        com.cloudbox.app.feature.upload.UploadProbeDialog(
            result = detail,
            onDismiss = {
                showFailureDetail = false
                // 连快照一起清，避免下次进来先闪一下上次的旧内容
                failureDetailText = ""
            },
            timeline = uploadTimeline
        )
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

/**
 * 操作菜单里的一行。
 *
 * 用整行可点区域而不是 AlertDialog 的 buttons —— AlertDialog 的
 * confirmButton/dismissButton 只放得下 1~2 个，8 个操作塞不进去，
 * 硬塞会被挤成两行、还会出现按钮文字换行。
 */
@Composable
private fun MenuAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 滚动到底自动续拉下一页（V31）。
 *
 * ## 为什么去掉"加载更多"按钮
 *
 * 旧实现底部是个「加载更多」按钮 + 「已全部加载」文字。用户反馈：
 * 每个文件夹底部都挂着"加载更多"，点进去却没有内容 —— 这两个问题同源。
 *
 * 根因在 `hasMore` 判错（见 `FileListResponse.hasMore` 的注释）：
 * 它依赖 `info` 字段，而那个字段在最后一页依然是 1，于是永远为 true。
 * 按钮就一直挂着，点一下拉到空页，按钮还在。
 *
 * 修掉判据之后，这里顺带对齐原版交互：原版**根本没有**"加载更多"按钮，
 * 而是**滚动到底自动加载**（`设置.auto_load` 开关名叫
 * 「自动加载页面剩余内容」，默认开，见 action_settings.lua:158）。
 * 手动按钮在多一屏文件的情况下纯属多余动作。
 *
 * ## 为什么用 LaunchedEffect 而不是监听滚动位置
 *
 * 这一项只有在**已经滚动到它**的时候才会被 Compose 组合出来（LazyColumn
 * 是懒加载的），所以"这个 item 被组合"本身就等价于"用户看到了列表底部"。
 * 比手动算 `lastVisibleItemIndex` 更简单，也不会漏掉"一屏没装满但仍需加载
 * 下一页"的情况。
 *
 * `loadingMore` 时只显示进度指示、不再触发请求 —— 这就是防重入闸门，
 * 不需要额外加锁。
 *
 * @param autoLoad 关掉时只显示进度条、不自动请求（用户在设置页明确表达了
 *                 "不要预读"，此时保留手动下拉刷新作为唯一入口）
 */
@Composable
private fun AutoLoadMoreRow(
    viewModel: FileListViewModel,
    state: FileListUiState,
    autoLoad: Boolean
) {
    LaunchedEffect(state.displayFiles.size, state.hasMore, autoLoad) {
        if (autoLoad && !state.loadingMore) viewModel.loadMore()
    }
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        // 仅"正在加载"时显示指示器。加载完成后这一行空白，
        // 不会在列表底部留下任何文字 —— 这正是用户要求的效果。
        if (state.loadingMore) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

/** 列表模式条目 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListItem(
    file: CloudFile,
    state: FileListUiState,
    viewModel: FileListViewModel,
    /** V30：是否显示文件类型标签（对齐原版 show_file_type_label） */
    showFileTypeLabel: Boolean = true,
    onOpenMenu: (CloudFile) -> Unit
) {
    val selected = file.id in state.selected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when {
                        // 多选态：轻点 = 勾选/取消
                        state.selectionMode -> viewModel.toggleSelect(file.id)
                        // 文件夹：直接进入（这是最自然的预期动作）
                        file.isFolder -> viewModel.enterFolder(file.id, file.name)
                        // 文件：弹操作菜单，而不是直接取分享。
                        // 原版就是这样（点文件出菜单：详情/分享/下载/重命名/删除…），
                        // 一上来只给分享框等于替用户决定了"你要干嘛"。
                        else -> onOpenMenu(file)
                    }
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
        // V30：类型标签（原版渲染在 格式卡片 里，文件夹不显示）
        if (showFileTypeLabel) {
            file.fileType?.let { FileTypeBadge(it) }
        }
        if (selected) {
            Icon(Icons.Filled.Check, "已选", tint = MaterialTheme.colorScheme.primary)
        } else if (!file.isFolder) {
            // 右侧"⋯"：同一点击行为，给不想猜"点一下会发生什么"的用户一个显式入口
            IconButton(onClick = { onOpenMenu(file) }) {
                Icon(Icons.Filled.MoreVert, "更多操作", Modifier.size(18.dp))
            }
        }
    }
}

/**
 * 文件类型标签小徽章（V30）。
 *
 * 单独抽出来是因为列表项和网格项都要用，且样式要保持一致 ——
 * 原版两处都用同一个 `格式文本` 控件。
 */
@Composable
private fun FileTypeBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(horizontal = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/** 网格模式条目 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridItem(
    file: CloudFile,
    state: FileListUiState,
    viewModel: FileListViewModel,
    /** V30：是否显示文件类型标签 */
    showFileTypeLabel: Boolean = true,
    onOpenMenu: (CloudFile) -> Unit
) {
    val selected = file.id in state.selected
    Column(
        modifier = Modifier
            .padding(6.dp)
            .combinedClickable(
                onClick = {
                    when {
                        state.selectionMode -> viewModel.toggleSelect(file.id)
                        file.isFolder -> viewModel.enterFolder(file.id, file.name)
                        else -> onOpenMenu(file)
                    }
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
        // V30：类型标签（原版两处布局都用同一个 格式文本 控件）
        if (showFileTypeLabel) {
            file.fileType?.let { FileTypeBadge(it) }
        }
    }
}
