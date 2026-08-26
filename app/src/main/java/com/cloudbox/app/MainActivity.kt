package com.cloudbox.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var clipboardWatcher: ClipboardLinkWatcher
    @Inject lateinit var settingsStore: SettingsStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动剪贴板链接监听（需求规格 9 节）
        clipboardWatcher.start(appScope)
        // #17：外部链接唤起（intent-filter 已限 lanzou 系 host），转交剪贴板弹窗机制
        intent?.data?.toString()?.let { clipboardWatcher.notifyLink(it) }

        enableEdgeToEdge()
        setContent {
            // 深色模式设置（设置页可切换：跟随系统/浅色/深色）
            val darkMode by settingsStore.darkMode.collectAsState(initial = "system")
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
                                onOpenResolve = { link ->
                                    // 路由参数必须 URL 编码（分享链接含 : / 等特殊字符）
                                    navController.navigate(Routes.RESOLVE.replace("{link}", Uri.encode(link ?: "")))
                                },
                                onLogout = {
                                    navController.navigate(Routes.LOGIN) {
                                        popUpTo(Routes.MAIN) { inclusive = true }
                                    }
                                }
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
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 模式复用 Activity：外部链接二次唤起走这里
        intent.data?.toString()?.let { clipboardWatcher.notifyLink(it) }
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
