package com.cloudbox.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import android.content.Context
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 设置页（需求规格 10 节）：域名配置入口 / UA / 后缀伪装 / 第三方解析 /
 * 深色模式 / 多账号管理 / Cookie 导出恢复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDomainConfig: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val uploadTimeline by viewModel.uploadTimeline.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // 真实文件自检：选完文件直接跑一遍上传链路
    val probePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.runUploadProbeWith(it) } }
    var uaDialog by remember { mutableStateOf(false) }
    var resolverDialog by remember { mutableStateOf(false) }
    var uaInput by remember { mutableStateOf(state.userAgent) }
    var resolverInput by remember { mutableStateOf(state.thirdPartyResolver) }
    // 账号中心设置（task=7/8/10/15）
    var extLinkDialog by remember { mutableStateOf(false) }
    var linkCodeDialog by remember { mutableStateOf(false) }
    var publisherDialog by remember { mutableStateOf(false) }
    var pwdDialog by remember { mutableStateOf(false) }
    var extLinkTitle by remember { mutableStateOf("") }
    var extLinkSummary by remember { mutableStateOf("") }
    var linkCodeEnabled by remember { mutableStateOf(true) }
    var linkCodeValue by remember { mutableStateOf("") }
    var publisherEnabled by remember { mutableStateOf(true) }
    var publisherName by remember { mutableStateOf("") }
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }

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
                title = { Text("设置") },
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
        ) {
            SectionTitle("账号管理（多账号）")
            state.accounts.forEach { acc ->
                val isCurrent = acc.uid == state.currentUid
                Row(
                    Modifier.fillMaxWidth().clickable { if (!isCurrent) viewModel.switchAccount(acc.uid) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isCurrent, onClick = { if (!isCurrent) viewModel.switchAccount(acc.uid) })
                    Column(Modifier.weight(1f)) {
                        Text(acc.uid, style = MaterialTheme.typography.bodyLarge)
                        Text("最近活跃：${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(acc.lastActiveAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { viewModel.removeAccount(acc.uid) }) { Text("删除") }
                }
            }
            HorizontalDivider()

            SectionTitle("Cookie")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Button(onClick = viewModel::exportCookies, modifier = Modifier.weight(1f)) { Text("导出到剪贴板") }
                Spacer(Modifier.height(0.dp))
                Spacer(Modifier.padding(start = 8.dp))
                Button(onClick = viewModel::restoreCookiesFromClipboard, modifier = Modifier.weight(1f)) { Text("从剪贴板恢复") }
            }
            HorizontalDivider()

            SectionTitle("网络与解析")
            SettingRow("User-Agent（桌面 UA 伪装）", state.userAgent.take(30)) { uaDialog = true }
            SettingRow("第三方直链解析服务", state.thirdPartyResolver.ifEmpty { "未配置（使用内置解析）" }) { resolverDialog = true }
            SettingRow("域名配置", "远程/手动覆盖/连通性测试") { onOpenDomainConfig() }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("上传后缀伪装", style = MaterialTheme.typography.bodyLarge)
                    Text("exe/apk 等自动改名为 .zip 上传，下载时还原", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.suffixSpoof, onCheckedChange = viewModel::saveSuffixSpoof)
            }
            // 上传通道：**没有开关**。App 一律自己拼 multipart 直传（原版就这么做，
            // 自检也实测通过）。网页上传降级为下面这个手动入口，只在需要时点开。
            SettingRow(
                "打开官方网页上传页（备用）",
                "传超大文件、或原生通道被风控挡住时才用；日常上传不用它"
            ) {
                context.startActivity(
                    android.content.Intent(
                        context,
                        com.cloudbox.app.feature.upload.WebViewUploadActivity::class.java
                    ).putExtra(
                        com.cloudbox.app.feature.upload.WebViewUploadActivity.EXTRA_FOLDER_ID,
                        -1L
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            HorizontalDivider()

            // ==================== 上传通道自检 ====================
            SectionTitle("上传通道自检")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("上传自检（探针）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "往根目录传一个 40 字节的 txt，并原样显示服务端回包。" +
                            "上传失败/假成功时点它，把结果截图发来即可定位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = viewModel::runUploadProbe, enabled = !state.probing) {
                    Text(if (state.probing) "检测中…" else "开始")
                }
            }
            // 拿真实文件测：内置探针只有 40 字节，它能过只说明链路通。
            // 真正失败的文件往往是太大/格式受限，必须用它自己测才暴露得出来。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("用真实文件自检", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "选那个一直传不上去的文件，按同样流程跑一遍并原样显示回包。" +
                            "这是定位问题最快的方式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { probePicker.launch(arrayOf("*/*")) }, enabled = !state.probing) {
                    Text(if (state.probing) "检测中…" else "选文件")
                }
            }
            state.probeResult?.let {
                com.cloudbox.app.feature.upload.UploadProbeDialog(it, onDismiss = viewModel::dismissProbe)
            }
            HorizontalDivider()

            // ==================== 上传日志（持久入口） ====================
            // 为什么放这里：上传失败时网盘页只弹一个 Snackbar，错过就没了
            // （用户实测"点看详情看不到"）。同一份时间线由 UploadTrace 单例
            // 持有，这里挂一个永久入口，任何时候都能回来翻、能复制。
            UploadTimelineSection(
                lines = uploadTimeline,
                onClear = viewModel::clearUploadTimeline
            )
            HorizontalDivider()

            // ==================== 账号中心设置（task=7/8/10/15，对齐原版 account.lua） ====================
            SectionTitle("账号中心设置")
            SettingRow("外链标题与简介", "个人主页展示的标题/简介（task=10）") { extLinkDialog = true }
            SettingRow("个人分享链访问码", "给个人主页分享链加一道码（task=7）") { linkCodeDialog = true }
            SettingRow("显示发布者", "是否在分享页露出昵称（task=15）") { publisherDialog = true }
            SettingRow("修改登录密码", "需验证旧密码，改后请重新登录（task=8）") { pwdDialog = true }
            HorizontalDivider()

            SectionTitle("深色模式")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                    Row(Modifier.clickable { viewModel.saveDarkMode(mode) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.darkMode == mode, onClick = { viewModel.saveDarkMode(mode) })
                        Text(label)
                    }
                }
            }
            HorizontalDivider()

            Spacer(Modifier.height(24.dp))
            Text("云匣 v0.1.0 · 仅供个人学习使用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
        }
    }

    if (uaDialog) {
        AlertDialog(
            onDismissRequest = { uaDialog = false },
            title = { Text("自定义 User-Agent") },
            text = {
                Column {
                    Text("默认桌面 Chrome UA；手机 UA 会触发蓝奏云隐藏 APK 等下载入口。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uaInput,
                        onValueChange = { uaInput = it },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveUserAgent(uaInput)
                    uaDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { uaDialog = false }) { Text("取消") } }
        )
    }
    if (resolverDialog) {
        AlertDialog(
            onDismissRequest = { resolverDialog = false },
            title = { Text("第三方解析服务 URL") },
            text = {
                Column {
                    Text("留空使用内置解析；服务需接受 POST {url, pwd?} 并返回 {\"url\": 直链}。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = resolverInput,
                        onValueChange = { resolverInput = it },
                        placeholder = { Text("https://your-resolver.example.com/api") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveThirdPartyResolver(resolverInput)
                    resolverDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { resolverDialog = false }) { Text("取消") } }
        )
    }

    // ---------- 外链标题与简介 task=10（ubt/usm） ----------
    if (extLinkDialog) {
        AlertDialog(
            onDismissRequest = { extLinkDialog = false },
            title = { Text("外链标题与简介") },
            text = {
                Column {
                    Text("对应服务端字段 ubt（标题）与 usm（简介），填写后立即生效。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = extLinkTitle, onValueChange = { extLinkTitle = it },
                        label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = extLinkSummary, onValueChange = { extLinkSummary = it },
                        label = { Text("简介（可留空）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setExternalLink(extLinkTitle, extLinkSummary)
                    extLinkDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { extLinkDialog = false }) { Text("取消") } }
        )
    }

    // ---------- 个人分享链访问码 task=7（codeoff/code，注意 codeoff 语义是反的） ----------
    if (linkCodeDialog) {
        AlertDialog(
            onDismissRequest = { linkCodeDialog = false },
            title = { Text("个人分享链访问码") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用访问码", Modifier.weight(1f))
                        Switch(checked = linkCodeEnabled, onCheckedChange = { linkCodeEnabled = it })
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = linkCodeValue, onValueChange = { linkCodeValue = it },
                        label = { Text("访问码") }, modifier = Modifier.fillMaxWidth(),
                        enabled = linkCodeEnabled)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setPersonalLinkCode(linkCodeEnabled, linkCodeValue)
                    linkCodeDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { linkCodeDialog = false }) { Text("取消") } }
        )
    }

    // ---------- 显示发布者 task=15（shows/shownames） ----------
    if (publisherDialog) {
        AlertDialog(
            onDismissRequest = { publisherDialog = false },
            title = { Text("显示发布者") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("在分享页显示发布者", Modifier.weight(1f))
                        Switch(checked = publisherEnabled, onCheckedChange = { publisherEnabled = it })
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = publisherName, onValueChange = { publisherName = it },
                        label = { Text("发布者昵称") }, modifier = Modifier.fillMaxWidth(),
                        enabled = publisherEnabled)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setPublisher(publisherEnabled, publisherName)
                    publisherDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { publisherDialog = false }) { Text("取消") } }
        )
    }

    // ---------- 修改密码 task=8（old_pwd/new_pwd，明文提交，沿用登录时的做法） ----------
    if (pwdDialog) {
        AlertDialog(
            onDismissRequest = { pwdDialog = false },
            title = { Text("修改登录密码") },
            text = {
                Column {
                    Text("旧密码会以明文提交（与原版一致）。修改成功后请重新登录。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = oldPwd, onValueChange = { oldPwd = it },
                        label = { Text("旧密码") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newPwd, onValueChange = { newPwd = it },
                        label = { Text("新密码（至少 6 位）") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.changePassword(oldPwd, newPwd)
                    oldPwd = ""
                    newPwd = ""
                    pwdDialog = false
                }) { Text("提交") }
            },
            dismissButton = { TextButton(onClick = { pwdDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.Filled.Language, null, Modifier.height(16.dp))
    }
}

/**
 * 上传日志（持久入口）。
 *
 * 时间线由 UploadTrace 单例持有，**跨页面、跨 Worker**：
 * ViewModel 建任务、WorkManager 调度、Worker 执行、回写结果，四段全在同一份
 * 时间线里，按时间戳顺序排好。上传失败但没来得及看 Snackbar 时，来这里翻。
 *
 * 判读要点（页面上也写了一份，免得每次都要翻代码）：
 * - 有「已拷贝…准备入队」→ SAF 读取这一段是通的；
 * - 有「Worker 启动」→ Worker 真的跑起来了（Hilt 注入 OK）；
 * - `ENQUEUED → FAILED` 且**没有**「Worker 启动」→ Worker 没被构造。
 */
@Composable
private fun UploadTimelineSection(lines: List<String>, onClear: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    SectionTitle("上传日志（失败原因在这里看）")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (lines.isEmpty()) "暂无记录" else "共 ${lines.size} 条（最新 ${lines.last().take(19)}）",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "上传链路的时间线：拷贝 → 入队 → 调度 → Worker → 服务端回包。" +
                    "「ENQUEUED 后直接 FAILED 且没有 Worker 启动」= Worker 没被构造。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "查看") }
    }

    if (expanded) {
        if (lines.isEmpty()) {
            Text(
                "还没有上传过，或日志已被清空。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        } else {
            // 时间线可能上百行，用一个高度受限的可滚动区域，避免把设置页撑爆
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Button(
                onClick = {
                    val text = if (lines.isEmpty()) "（上传日志为空）"
                    else lines.joinToString("\n")
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("上传日志", text))
                },
                modifier = Modifier.weight(1f)
            ) { Text("复制全部") }
            Spacer(Modifier.padding(start = 8.dp))
            Button(onClick = onClear, modifier = Modifier.weight(1f)) { Text("清空") }
        }
    }
}
