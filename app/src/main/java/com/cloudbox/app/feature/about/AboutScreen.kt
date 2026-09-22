package com.cloudbox.app.feature.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.cloudbox.app.common.DownloadHelper
import com.cloudbox.app.core.domain.model.UpdateLogEntry

/**
 * 关于页（对齐原版 `about.lua` + `update_log.lua`）。
 *
 * 原版「我的 → 关于」里有：版本号、检查更新、更新日志、常见问题、许可、反馈。
 * 本页对应实现：
 * - 版本号：取 `BuildConfig.VERSION_NAME/VERSION_CODE`（CI 注入）
 * - 检查更新：数字版号比较 + 下载安装（GitHub Releases）
 * - 使用帮助：常见问题（内置，原版外链的兔小巢已停运）+ 反馈入口
 * - 法律信息：开源许可清单
 * - 更新日志：本项目自维护
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    viewModel: AboutViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showFaq by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("云匣", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "版本 ${state.versionName}（build ${state.versionCode}）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "个人自用的蓝奏云第三方客户端。仅供个人学习使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            val installContext = LocalContext.current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = viewModel::checkUpdate, enabled = !state.checking) {
                    if (state.checking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("检查更新")
                    }
                }
                state.updateResult?.let { r ->
                    Text(
                        r,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.contains("发现新版本")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 检测到更新：展示版本说明 + 下载 / 进度 / 安装
            state.update?.let { update ->
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "新版本 ${update.versionName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!update.publishedAt.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                update.publishedAt,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (update.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(update.releaseNotes, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(10.dp))
                        val downloaded = state.downloadedFile
                        when {
                            downloaded != null -> Button(
                                onClick = { DownloadHelper.installApkFromFile(installContext, downloaded) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("安装") }

                            state.downloading -> {
                                LinearProgressIndicator(
                                    progress = state.progress / 100f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "下载中 ${state.progress}%",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            else -> Button(
                                onClick = viewModel::download,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("下载更新") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("项目地址", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "github.com/d1667018881/cloudbox",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenUrl("https://github.com/d1667018881/cloudbox") }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("使用帮助", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    AboutEntry("常见问题") { showFaq = true }
                    AboutEntry("反馈问题") {
                        onOpenUrl("https://github.com/d1667018881/cloudbox/issues")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("法律信息", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    AboutEntry("开源许可") { showLicense = true }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("更新日志", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            AboutViewModel.UPDATE_LOG.forEach { entry ->
                UpdateLogItem(entry)
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "本 App 为个人学习用途的第三方客户端，与原版作者无关。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showFaq) {
        AlertDialog(
            onDismissRequest = { showFaq = false },
            title = { Text("常见问题") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AboutViewModel.FAQ.forEach { (q, a) ->
                        Text(q, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            a,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFaq = false }) { Text("关闭") } }
        )
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("开源许可") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AboutViewModel.LICENSES.forEach { (name, lic) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                lic,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } }
        )
    }
}

@Composable
private fun AboutEntry(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun UpdateLogItem(entry: UpdateLogEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                entry.version,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            entry.lines.forEach { line ->
                Text(
                    "· $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}
