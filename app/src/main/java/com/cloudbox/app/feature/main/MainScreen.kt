package com.cloudbox.app.feature.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cloudbox.app.common.ClipboardLinkWatcher
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.feature.filelist.FileListScreen
import com.cloudbox.app.feature.resolve.ResolveScreen
import com.cloudbox.app.feature.search.SearchViewModel

/**
 * 主界面：底部导航容器（网盘 / 解析 / 上传 / 我的）。
 * 同时承载剪贴板链接识别弹窗（需求规格 9 节）。
 */
@Composable
fun MainScreen(
    onOpenSearch: () -> Unit,
    onOpenRecycle: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenResolve: (String?) -> Unit,
    onLogout: () -> Unit,
    clipboardWatcher: ClipboardLinkWatcher = hiltViewModel<MainViewModel>().clipboardWatcher,
    authRepository: AuthRepository = hiltViewModel<MainViewModel>().authRepository,
    searchViewModel: SearchViewModel = hiltViewModel()
) {
    var tab by remember { mutableIntStateOf(0) }
    val pendingLink by clipboardWatcher.pendingLink.collectAsState()
    // currentAccount 是 Flow（非 StateFlow），collectAsState 必须提供 initial
    val account by authRepository.currentAccount.collectAsState(initial = null)

    // Android 10+ 从其他 App 复制链接再切回本 App 时，系统回调不会触发，
    // 必须在每次回到前台时主动补查一次剪贴板（需求规格 9 节）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) clipboardWatcher.checkNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 后台自动同步搜索索引（需求规格 3 节：Room FTS 索引，后台自动同步）
    LaunchedEffect(Unit) {
        if (!searchViewModel.uiState.value.syncing) {
            searchViewModel.syncAll()
        }
    }

    // 剪贴板检测到分享链接 → 弹窗询问是否解析
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
                TextButton(onClick = { clipboardWatcher.dismiss() }) { Text("忽略") }
            }
        )
    }

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
                    onOpenRecycle = onOpenRecycle
                )
                1 -> ResolveScreen(onBack = {})
                2 -> MeTab(
                    accountName = account?.uid ?: "未登录",
                    onOpenDownload = onOpenDownload,
                    onOpenFavorites = onOpenFavorites,
                    onOpenRecycle = onOpenRecycle,
                    onOpenSettings = onOpenSettings,
                    onLogout = onLogout
                )
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
    onLogout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("当前账号：$accountName", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        MeEntry("下载管理", onOpenDownload)
        MeEntry("收藏夹", onOpenFavorites)
        MeEntry("回收站", onOpenRecycle)
        MeEntry("设置", onOpenSettings)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onLogout) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MeEntry(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}
