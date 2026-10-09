package com.cloudbox.app.feature.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cloudbox.app.common.ClipboardLinkWatcher
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.feature.filelist.FileListScreen
import com.cloudbox.app.feature.search.SearchViewModel
import kotlinx.coroutines.launch

/**
 * 主界面：抽屉（侧栏）+ 单页网盘。
 *
 * V43（2026-09-30）：底部三 tab 拆除——「我的」与抽屉功能 100% 重叠，
 * 「解析」收进侧栏（点击单开 Routes.RESOLVE 独立页），只剩网盘一页后
 * 底部导航栏无存在意义。本组件仍承载剪贴板链接识别弹窗（需求规格 9 节）。
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
    /** V51：抽屉「扫描二维码」→ 解析页自动唤起相机 */
    onOpenResolveScan: () -> Unit = {},
    /** V51：切换账户弹窗「添加账号」→ 登录页 */
    onAddAccount: () -> Unit = {},
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
    // V51：抽屉「切换账户」弹窗（对齐原版 home_func.lua「选择已有账号登录」）
    var showSwitchAccount by remember { mutableStateOf(false) }
    val switchAccounts by mainViewModel.accounts.collectAsState()
    val switchMsg by mainViewModel.switchMsg.collectAsState()
    LaunchedEffect(switchMsg) {
        switchMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            mainViewModel.consumeSwitchMsg()
            showSwitchAccount = false
        }
    }
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

    // V51：切换账户弹窗 —— 已存账号列表（点击切换 / 长按或按钮删除）+ 添加新账号。
    // 对齐原版「选择已有账号登录」：cookie 直接切，无密码输入（失效时切完在
    // 网盘页会收到登录态错误，走退出重登）。
    if (showSwitchAccount) {
        AlertDialog(
            onDismissRequest = { showSwitchAccount = false },
            title = { Text("选择已有账号登录") },
            text = {
                Column {
                    if (switchAccounts.isEmpty()) {
                        Text("还没有已保存的账号")
                    } else {
                        switchAccounts.forEach { acc ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = acc.uid == account?.uid,
                                    onClick = {
                                        if (acc.uid != account?.uid) mainViewModel.switchAccount(acc.uid)
                                    }
                                )
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable {
                                            if (acc.uid != account?.uid) mainViewModel.switchAccount(acc.uid)
                                        }
                                ) {
                                    Text(acc.uid, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "最近活跃：" + java.text.SimpleDateFormat(
                                            "MM-dd HH:mm", java.util.Locale.getDefault()
                                        ).format(java.util.Date(acc.lastActiveAt)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { mainViewModel.removeAccount(acc.uid) }) { Text("删除") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSwitchAccount = false; onAddAccount() }) { Text("添加账号") }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchAccount = false }) { Text("关闭") }
            }
        )
    }

    // V30：读取界面显示设置 —— 2026-09-30（V43）「我的」tab 移除后，
    // showAccountButton / showFileTypeLabel 都只服务 MeTab，MainScreen 不再消费；
    // FileListScreen 自己读 showFileTypeLabel，不受影响。

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
    // V41（N9）：局部快照替代 if 判空 + lambda 内 `!!`——delegated state 无法
    // 智能转换才用了 `!!`；子组合读到晚一拍快照时存在理论 NPE 面（dismiss 置
    // null 与弹窗内容组合的交错）。快照后整棵子树只引用不可变局部值。
    val detectedLink = pendingLink
    if (detectedLink != null) {
        AlertDialog(
            onDismissRequest = { clipboardWatcher.dismiss() },
            title = { Text("检测到分享链接") },
            text = { Text(detectedLink, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
                onOpenResolve = { onOpenResolve(null) },
                // V51（对齐原版抽屉条目）：扫码/切换账户一级入口
                onScanQr = { onOpenResolveScan() },
                onSwitchAccount = { showSwitchAccount = true; mainViewModel.loadAccounts() },
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
    // V43（2026-09-30）：底部三 tab（网盘/解析/我的）拆掉 ——
    // ·「我的」与抽屉功能 100% 重叠（账号/下载/收藏/回收站/设置/关于/退出全在侧栏）
    // ·「解析」改为侧栏入口，点击单开独立页面（Routes.RESOLVE 本就存在，
    //   剪贴板弹窗一直走它）
    // 只剩网盘一页后，底部导航栏失去存在意义，整个 Scaffold 简化为单页。
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FileListScreen(
                onOpenSearch = onOpenSearch,
                onOpenRecycle = onOpenRecycle,
                onOpenDownload = onOpenDownload,
                onOpenAnnouncement = onOpenAnnouncement,
                announcementUnread = announcementUnread,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                // 外部分享进来的文件/链接：由网盘页消费（文件直接传当前目录）
                pendingShare = pendingShare,
                onShareConsumed = onShareConsumed,
                onOpenSharedLink = { link -> onOpenResolve(link) }
            )
        }
    }
}
}
