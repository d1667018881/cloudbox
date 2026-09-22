package com.cloudbox.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cloudbox.app.common.ClipboardLinkWatcher
import com.cloudbox.app.common.ShareIntentHandler
import com.cloudbox.app.core.data.announcement.AnnouncementStatusStore
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.data.remote.CookiePersistenceJar
import com.cloudbox.app.core.data.update.UpdateStatusStore
import com.cloudbox.app.core.domain.repository.AnnouncementRepository
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.core.domain.repository.UpdateRepository
import com.cloudbox.app.feature.download.DownloadScreen
import com.cloudbox.app.feature.favorites.FavoritesScreen
import com.cloudbox.app.feature.domain.DomainConfigScreen
import com.cloudbox.app.feature.login.LoginScreen
import com.cloudbox.app.feature.main.MainScreen
import com.cloudbox.app.feature.recycle.RecycleScreen
import com.cloudbox.app.feature.resolve.ResolveScreen
import com.cloudbox.app.feature.search.SearchScreen
import com.cloudbox.app.feature.settings.SettingsScreen
import com.cloudbox.app.ui.theme.CloudBoxTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 导航路由常量 */
object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val DOMAIN_CONFIG = "domain_config"
    const val RESOLVE = "resolve?link={link}"
    const val SEARCH = "search"
    /** 搜索结果的落地页：直接打开某个目录（folderName 仅用于面包屑显示） */
    const val FILELIST = "filelist?folderId={folderId}&folderName={folderName}"
    const val DOWNLOAD = "download"
    const val FAVORITES = "favorites"
    /** 星标文件夹（自盘常用目录聚合，对齐原版） */
    const val STARRED = "starred"
    const val RECYCLE = "recycle"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    /** 账号面板（原版 account.lua 的「管理账号」） */
    const val ACCOUNT = "account"
    /** 公告（自建，替代原版已停运的第三方公告页） */
    const val ANNOUNCEMENT = "announcement"
    /** 查看全盘文件（原版「查看全盘文件」） */
    const val FULLLOAD = "fullload"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var clipboardWatcher: ClipboardLinkWatcher
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var cookieJar: CookiePersistenceJar
    @Inject lateinit var updateRepository: UpdateRepository
    @Inject lateinit var announcementRepository: AnnouncementRepository
    @Inject lateinit var updateStatusStore: UpdateStatusStore
    @Inject lateinit var announcementStatusStore: AnnouncementStatusStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 待处理的外部分享内容（文件 / 链接）。
     *
     * 为什么用 StateFlow 而不是直接回调：分享进来的时机可能早于
     * Compose 侧挂载（如冷启动），用一个"可被后续读取"的容器把它暂存，
     * UI 侧一挂载就能消费掉，不会丢。
     */
    private val _pendingShare = MutableStateFlow<ShareIntentHandler.SharedContent?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户拒绝仅影响下载通知显示，不阻塞主流程 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动剪贴板链接监听（需求规格 9 节）
        clipboardWatcher.start(appScope)
        // V33：订阅「剪贴板识别」开关（原版 get_clipboard），关掉后不再弹识别框
        appScope.launch {
            settingsStore.getClipboard.collect { clipboardWatcher.setEnabled(it) }
        }
        // #17：外部链接唤起（intent-filter 已限 lanzou 系 host），转交剪贴板弹窗机制
        intent?.data?.toString()?.let { clipboardWatcher.notifyLink(it) }
        // 从其他 App 分享文件/链接进来（ACTION_SEND / ACTION_SEND_MULTIPLE）
        handleShareIntent(intent)
        // V33：后台检查更新与公告（红点数据源），失败静默，不影响主流程
        checkUpdateAndAnnouncements()
        // Android 13+ 动态请求通知权限（下载完成/上传进度通知需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED -> {}
                else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        enableEdgeToEdge()
        setContent {
            // 深色模式设置（设置页可切换：跟随系统/浅色/深色）
            val darkMode by settingsStore.darkMode.collectAsState(initial = "system")
            // 应用内语言（设置页可切换：跟随系统/中文/English）。
            // 用 wrap() 套一层带 Locale 的 Context，让本 Activity 重启后按新语言渲染。
            val appLanguage by settingsStore.appLanguage.collectAsState(initial = "system")
            val localizedContext = androidx.compose.runtime.remember(appLanguage) {
                com.cloudbox.app.common.LocaleUtil.wrap(this@MainActivity, appLanguage)
            }
            // 外部分享进来的内容（文件优先）；主界面挂载后会消费并触发上传
            val pendingShare by _pendingShare.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localizedContext
            ) {
            CloudBoxTheme(darkMode = darkMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 冷启动免二次登录：起始页按「是否有可用的已恢复登录态」决定。
                    // 判定是纯本地的 —— AuthRepositoryImpl.init 在构造时已把上次账号的
                    // Cookie 槽位恢复进内存，这里只读一下，不发网络；真过期由 CloudBoxApp
                    // 的后台 ensureSession() 静默重登兜底，兜不住时各页面的 CookieExpired
                    // 处理会把人送回登录页。
                    var startRoute by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        val hasAccount = runCatching {
                            authRepository.currentAccount.first()
                        }.getOrNull() != null
                        val loggedIn = runCatching { cookieJar.isLoggedIn() }.getOrDefault(false)
                        startRoute = if (hasAccount && loggedIn) Routes.MAIN else Routes.LOGIN
                    }
                    val resolvedStart = startRoute
                    if (resolvedStart == null) {
                        // 判定只花毫秒级；这段时间留白，避免先闪一下登录页
                    } else {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = resolvedStart
                    ) {
                        composable(Routes.LOGIN) {
                            LoginScreen(
                                onLoginSuccess = {
                                    navController.navigate(Routes.MAIN) {
                                        popUpTo(Routes.LOGIN) { inclusive = true }
                                    }
                                },
                                onOpenDomainConfig = { navController.navigate(Routes.DOMAIN_CONFIG) }
                            )
                        }
                        composable(Routes.MAIN) {
                            MainScreen(
                                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                                onOpenRecycle = { navController.navigate(Routes.RECYCLE) },
                                onOpenDownload = { navController.navigate(Routes.DOWNLOAD) },
                                onOpenFavorites = { navController.navigate(Routes.FAVORITES) },
                                // 星标文件夹（自盘聚合）——入口与收藏夹并列
                                onOpenStarred = { navController.navigate(Routes.STARRED) },
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                                onOpenAnnouncement = { navController.navigate(Routes.ANNOUNCEMENT) },
                                onOpenFullLoad = { navController.navigate(Routes.FULLLOAD) },
                                onOpenResolve = { link ->
                                    // 路由参数必须 URL 编码（分享链接含 : / 等特殊字符）
                                    navController.navigate(Routes.RESOLVE.replace("{link}", Uri.encode(link ?: "")))
                                },
                                onLogout = {
                                    navController.navigate(Routes.LOGIN) {
                                        popUpTo(Routes.MAIN) { inclusive = true }
                                    }
                                },
                                // 外部分享进来的文件/链接：交给主界面消费（触发上传或跳解析）
                                pendingShare = pendingShare,
                                onShareConsumed = { _pendingShare.value = null }
                            )
                        }
                        composable(Routes.DOMAIN_CONFIG) {
                            DomainConfigScreen(onBack = { navController.popBackStack() })
                        }
                        composable(
                            Routes.RESOLVE,
                            arguments = listOf(navArgument("link") { type = NavType.StringType; defaultValue = "" })
                        ) { entry ->
                            val link = entry.arguments?.getString("link")?.takeIf { it.isNotBlank() }
                                ?.let { Uri.decode(it) }
                            ResolveScreen(onBack = { navController.popBackStack() }, initialLink = link)
                        }
                        composable(Routes.SEARCH) {
                            SearchScreen(
                                onBack = { navController.popBackStack() },
                                // 点搜索结果 → 落到对应目录（文件夹本身 / 文件所在目录）。
                                // 旧实现结果行没有 onClick，搜出来只能看不能进 —— 这就是
                                // 「搜索能搜不能用」的直接原因。
                                onOpenFolder = { id, name ->
                                    navController.navigate(
                                        Routes.FILELIST
                                            .replace("{folderId}", id.toString())
                                            .replace("{folderName}", Uri.encode(name))
                                    )
                                },
                                onOpenFileInParent = { parentId ->
                                    navController.navigate(
                                        Routes.FILELIST
                                            .replace("{folderId}", parentId.toString())
                                            .replace("{folderName}", "")
                                    )
                                }
                            )
                        }
                        // 搜索结果的落地页：直接打开指定目录的浏览页
                        composable(
                            Routes.FILELIST,
                            arguments = listOf(
                                navArgument("folderId") { type = NavType.LongType; defaultValue = -1L },
                                navArgument("folderName") { type = NavType.StringType; defaultValue = "" }
                            )
                        ) { entry ->
                            com.cloudbox.app.feature.filelist.FileListScreen(
                                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                                onOpenRecycle = { navController.navigate(Routes.RECYCLE) },
                                onOpenAnnouncement = { navController.navigate(Routes.ANNOUNCEMENT) },
                                onOpenSharedLink = { link ->
                                    navController.navigate(Routes.RESOLVE.replace("{link}", Uri.encode(link)))
                                },
                                initialFolderId = entry.arguments?.getLong("folderId"),
                                initialFolderName = entry.arguments?.getString("folderName").orEmpty()
                            )
                        }
                        composable(Routes.DOWNLOAD) {
                            DownloadScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Routes.FAVORITES) {
                            FavoritesScreen(
                                onBack = { navController.popBackStack() },
                                onOpenShare = { url ->
                                    navController.navigate(Routes.RESOLVE.replace("{link}", Uri.encode(url)))
                                }
                            )
                        }
                        composable(Routes.RECYCLE) {
                            RecycleScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Routes.STARRED) {
                            com.cloudbox.app.feature.starred.StarredFoldersScreen(
                                onBack = { navController.popBackStack() },
                                // 点星标项 → 打开对应目录（复用搜索结果的落地页路由）
                                onOpenFolder = { id, name ->
                                    navController.navigate(
                                        Routes.FILELIST
                                            .replace("{folderId}", id.toString())
                                            .replace("{folderName}", Uri.encode(name))
                                    )
                                }
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onOpenDomainConfig = { navController.navigate(Routes.DOMAIN_CONFIG) }
                            )
                        }
                        composable(Routes.ABOUT) {
                            com.cloudbox.app.feature.about.AboutScreen(
                                onBack = { navController.popBackStack() },
                                onOpenUrl = { url ->
                                    // 用系统浏览器打开项目地址（App 内不内嵌 WebView，
                                    // 避免为一个跳转引入一个 WebView 容器与其安全问题）
                                    runCatching {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                }
                            )
                        }
                        composable(Routes.ACCOUNT) {
                            com.cloudbox.app.feature.account.AccountScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Routes.ANNOUNCEMENT) {
                            com.cloudbox.app.feature.announcement.AnnouncementScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Routes.FULLLOAD) {
                            com.cloudbox.app.feature.fullload.FullLoadScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                    } // 结束 if/else（起始页判定）
                }
            }
            } // 结束 CompositionLocalProvider（应用内语言）
        }
    }

    /**
     * 启动后台检查「更新」与「公告」，结果写入两个 StatusStore 供红点显示。
     *
     * 用 appScope（IO）而非 lifecycleScope：这两件事与界面无关，Activity 重建/销毁
     * 不应打断；失败一律静默（红点不显示即可），绝不影响主流程。
     */
    private fun checkUpdateAndAnnouncements() {
        appScope.launch {
            updateRepository.checkUpdate().onSuccess { updateStatusStore.set(it) }
        }
        appScope.launch {
            announcementRepository.fetch().onSuccess { list ->
                val latest = list.maxByOrNull { it.date.orEmpty() }
                val lastRead = announcementRepository.lastReadId()
                announcementStatusStore.setUnread(latest != null && latest.id != lastRead)
            }
        }
    }

    /**
     * 处理「从其他 App 分享进来」的 intent（文件或文本链接）。
     *
     * 两件事必须在这里做：
     * 1. **对 content:// URI 申请持久读权限** —— 上传是在 WorkManager 里
     *    后台跑的，Activity 的临时授权那时可能已失效，不申请就会
     *    SecurityException（表现为"上传失败但看不出原因"）。
     * 2. 文本形态若无文件，交给剪贴板机制走链接解析（复用既有弹窗）。
     */
    private fun handleShareIntent(intent: Intent?) {
        val content = ShareIntentHandler.parse(intent)
        if (content.isEmpty) return

        if (content.hasFiles) {
            // 持久授权：takePersistableUriPermission 可能因 provider 不支持而抛异常，
            // 抛了也不致命（临时授权在同进程/短时间内仍有效），所以只记日志不中断。
            var granted = 0
            ShareIntentHandler.urisNeedingPersistablePermission(content.fileUris).forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    granted++
                }.onFailure {
                    Log.w("CloudBoxUpload", "持久读权限申请失败（不致命）：$uri —— ${it.javaClass.simpleName}")
                }
            }
            Log.i("CloudBoxUpload", "分享文件已接收：${content.fileUris.size} 个，持久授权成功 $granted 个")
            _pendingShare.value = content
        } else {
            // 纯文本分享 → 按链接处理，复用剪贴板弹窗机制（会自动识别并提示打开）
            content.text?.let { clipboardWatcher.notifyLink(it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 模式复用 Activity：外部链接二次唤起走这里
        intent.data?.toString()?.let { clipboardWatcher.notifyLink(it) }
        // App 已在前台时再从别的 App 分享文件过来（不会重建 Activity，只走这里）
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // #16 修复：前台主动查一次剪贴板（弥补 Android 10+ 监听回调的局限性）
        clipboardWatcher.checkNow()
    }

    override fun onDestroy() {
        clipboardWatcher.stop()
        super.onDestroy()
    }
}
