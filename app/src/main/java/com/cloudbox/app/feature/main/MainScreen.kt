package com.cloudbox.app.feature.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cloudbox.app.common.ClipboardLinkWatcher
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.feature.filelist.FileListScreen
import com.cloudbox.app.feature.resolve.ResolveScreen
import com.cloudbox.app.feature.search.SearchViewModel
import kotlinx.coroutines.launch

/**
 * 主界面：抽屉（侧栏）+ 底部导航容器（网盘 / 解析 / 我的）。
 * 抽屉对齐原版侧滑栏；底部 tab 保留 CloudBox 既有的三页结构。
 * 同时承载剪贴板链接识别弹窗（需求规格 9 节）。
 */
@Composable
fun MainScreen(
    onOpenSearch: () -> Unit,
    onOpenRecycle: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenStarred: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAnnouncement: () -> Unit,
    onOpenFullLoad: () -> Unit,
    onOpenResolve: (String?) -> Unit,
    onLogout: () -> Unit,
    /** 从其他 App 分享进来的文件/链接（未消费时非空），透传给网盘页消费 */
    pendingShare: com.cloudbox.app.common.ShareIntentHandler.SharedContent? = null,
    /** 分享内容已消费，通知上层清空（防重组重复触发） */
    onShareConsumed: () -> Unit = {},
    clipboardWatcher: ClipboardLinkWatcher = hiltViewModel<MainViewModel>().clipboardWatcher,
    authRepository: AuthRepository = hiltViewModel<MainViewModel>().authRepository,
    settingsStore: SettingsStore = hiltViewModel<MainViewModel>().settingsStore,
    searchViewModel: SearchViewModel = hiltViewModel()
) {
    var tab by remember { mutableIntStateOf(0) }
    val pendingLink by clipboardWatcher.pendingLink.collectAsState()
    // currentAccount 是 Flow（非 StateFlow），collectAsState 必须提供 initial
    val account by authRepository.currentAccount.collectAsState(initial = null)

    val scope = rememberCoroutineScope()
    val mainViewModel: MainViewModel = hiltViewModel()
    val context = LocalContext.current
    // V33：红点数据源（更新 / 公告），由启动检查写入
    val updateAvailable by mainViewModel.updateStatusStore.available.collectAsState()
    val announcementUnread by mainViewModel.announcementStatusStore.unread.collectAsState()

    // 「退出登录」必须**先真正注销**，再切页面：旧实现只换界面不清 Cookie，
    // 登录页一挂载就观察到 currentAccount != null，又跳回主页（表现为"闪一下又进来"）。
    // 详见 AuthRepositoryImpl.logout 的注释。
    //
    // ⚠️ 显式标注 `: () -> Unit`：`scope.launch{}` 返回 Job，不标注会被推断成
    //    `() -> Job`，传给 TextButton 的 `() -> Unit` 形参就编译报错。
    val doLogout: () -> Unit = {
        scope.launch {
            account?.uid?.let { authRepository.logout(it) }
            onLogout()
        }
        Unit
    }

    var confirmLogout by remember { mutableStateOf(false) }
    // 退出登录不可撤销（会清掉该账号保存的密码与 Cookie），所以先问一句。
    // ⚠️ V33 修复：此前 `confirmLogout` 从来没有被置 true —— 确认框是**死代码**，
    //    点退出是直接执行的。现在所有退出入口都先把它置 true。
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = {
                Text(
                    "将清除账号「${account?.uid ?: "当前账号"}」在本机保存的登录凭证" +
                        "与密码（云端数据不受影响）。确定退出？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    doLogout()
                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            }
        )
    }

    // V30：读取界面显示设置（initial 用与原版一致的默认值，避免首帧闪动）
    val showAccountButton by settingsStore.showAccountButton.collectAsState(initial = true)
    val showFileTypeLabel by settingsStore.showFileTypeLabel.collectAsState(initial = false)

    // Android 10+ 从其他 App 复制链接再切回本 App 时系统回调不触发，回前台时补查一次剪贴板
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) clipboardWatcher.checkNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 后台自动同步搜索索引
    LaunchedEffect(Unit) {
        if (!searchViewModel.uiState.value.syncing) {
            searchViewModel.syncAll()
        }
    }

    // 剪贴板检测到分享链接 → 弹窗：解析 / 获取直链 / 忽略
    if (pendingLink != null) {
        AlertDialog(
            onDismissRequest = { clipboardWatcher.dismiss() },
            title = { Text("检测到分享链接") },
            text = { Text(pendingLink!!, maxLines = 2) },
            confirmButton = {
                TextButton(onClick = {
                    clipboardWatcher.consume()?.let { onOpenResolve(it) }
                }) { Text("解析") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val link = pendingLink ?: return@TextButton
                        mainViewModel.resolveDirectLink(link) { ok, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            if (ok) clipboardWatcher.dismiss()
                        }
                    }) { Text("获取直链") }
                    TextButton(onClick = { clipboardWatcher.dismiss() }) { Text("忽略") }
                }
            }
        )
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                accountName = account?.uid ?: "未登录",
                updateAvailable = updateAvailable != null,
                announcementUnread = announcementUnread,
                onClose = { scope.launch { drawerState.close() } },
                onOpenFullLoad = onOpenFullLoad,
                onOpenDownload = onOpenDownload,
                onOpenFavorites = onOpenFavorites,
                onOpenStarred = onOpenStarred,
                onOpenRecycle = onOpenRecycle,
                onOpenAccount = onOpenAccount,
                onOpenAnnouncement = onOpenAnnouncement,
                onOpenSettings = onOpenSettings,
                onOpenAbout = onOpenAbout,
                onLogout = { confirmLogout = true }
            )
        }
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.CloudUpload, null) },
                        label = { Text("网盘") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Link, null) },
                        label = { Text("解析") }
                    )
                    // ⚠️ 原「上传」tab 已于 2026-09-15 移除（V32 死代码清理）：
                    //    网盘页 FAB 已能"传到当前目录"，独立上传页属重复入口。
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Person, null) },
                        label = { Text("我的") }
                    )
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> FileListScreen(
                        onOpenSearch = onOpenSearch,
                        onOpenRecycle = onOpenRecycle,
                        onOpenAnnouncement = onOpenAnnouncement,
                        announcementUnread = announcementUnread,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        // 外部分享进来的文件/链接：由网盘页消费（文件直接传当前目录）
                        pendingShare = pendingShare,
                        onShareConsumed = onShareConsumed,
                        onOpenSharedLink = { link -> onOpenResolve(link) }
                    )
                    1 -> ResolveScreen(onBack = {})
                    2 -> MeTab(
                        accountName = account?.uid ?: "未登录",
                        onOpenDownload = onOpenDownload,
                        onOpenFavorites = onOpenFavorites,
                        onOpenRecycle = onOpenRecycle,
                        onOpenSettings = onOpenSettings,
                        onOpenAbout = onOpenAbout,
                        onOpenAccount = onOpenAccount,
                        onLogout = { confirmLogout = true },
                        showAccountButton = showAccountButton,
                        showFileTypeLabel = showFileTypeLabel,
                        updateAvailable = updateAvailable != null
                    )
                }
            }
        }
    }
}

@Composable
private fun MeTab(
    accountName: String,
    onOpenDownload: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecycle: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAccount: () -> Unit,
    onLogout: () -> Unit,
    /** V30：是否显示账号入口（原版 show_account_button） */
    showAccountButton: Boolean = true,
    /** V30：是否在条目上显示类型标签（原版 show_file_type_label） */
    showFileTypeLabel: Boolean = false,
    /** V33：关于入口是否有更新（红点） */
    updateAvailable: Boolean = false
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (showAccountButton) {
            Text("当前账号：$accountName", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
        }
        MeEntry("账号信息", onOpenAccount)
        MeEntry("下载管理", onOpenDownload)
        MeEntry("收藏夹" + if (showFileTypeLabel) "（文件夹可检查更新）" else "", onOpenFavorites)
        MeEntry("回收站", onOpenRecycle)
        MeEntry("设置", onOpenSettings)
        // 关于页（对齐原版 about.lua）：版本号 / 检查更新
        MeEntry("关于", onOpenAbout, badge = updateAvailable)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onLogout) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MeEntry(title: String, onClick: () -> Unit, badge: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (badge) {
            Spacer(Modifier.width(8.dp))
            Badge()
        }
    }
}
