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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cloudbox.app.common.ClipboardLinkWatcher
import com.cloudbox.app.common.ShareIntentHandler
import com.cloudbox.app.core.data.local.datastore.SettingsStore
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
import javax.inject.Inject

/** 导航路由常量 */
object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val DOMAIN_CONFIG = "domain_config"
    const val RESOLVE = "resolve?link={link}"
    const val SEARCH = "search"
    const val DOWNLOAD = "download"
    const val FAVORITES = "favorites"
    const val RECYCLE = "recycle"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var clipboardWatcher: ClipboardLinkWatcher
    @Inject lateinit var settingsStore: SettingsStore

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
        // #17：外部链接唤起（intent-filter 已限 lanzou 系 host），转交剪贴板弹窗机制
        intent?.data?.toString()?.let { clipboardWatcher.notifyLink(it) }
        // 从其他 App 分享文件/链接进来（ACTION_SEND / ACTION_SEND_MULTIPLE）
        handleShareIntent(intent)
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
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = Routes.LOGIN
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
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                onOpenAbout = { navController.navigate(Routes.ABOUT) },
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
                            SearchScreen(onBack = { navController.popBackStack() })
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
                    }
                }
            }
            } // 结束 CompositionLocalProvider（应用内语言）
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
