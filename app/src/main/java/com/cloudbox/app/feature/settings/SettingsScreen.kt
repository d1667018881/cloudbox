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
import androidx.compose.material3.FilterChip
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
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var uaDialog by remember { mutableStateOf(false) }
    var resolverDialog by remember { mutableStateOf(false) }
    // 自定义伪装后缀列表
    var spoofDialog by remember { mutableStateOf(false) }
    var spoofInput by remember { mutableStateOf("") }
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
    // 数据管理（V32）
    var resetConfirm by remember { mutableStateOf(false) }

    // 恢复数据的文件选择器。用 OpenDocument 而不是 GetContent：
    // 前者返回的 uri 在 Activity 重建后依然可读（系统会给持久读权限），
    // 后者只在本次会话内有效，旋转屏幕后再读会 SecurityException。
    val restorePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::prepareRestore) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    // 打开伪装后缀对话框时，把当前配置填进输入框（只填一次，不覆盖用户正在编辑的内容）
    LaunchedEffect(spoofDialog) {
        if (spoofDialog && spoofInput.isBlank()) spoofInput = state.spoofSuffixList
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
                Column(
                    Modifier.weight(1f).clickable { spoofDialog = true }
                ) {
                    Text("上传后缀伪装", style = MaterialTheme.typography.bodyLarge)
                    Text("以下格式自动改名为 .zip 上传，下载时还原（点击编辑列表）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("当前：${state.spoofSuffixList}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
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

            // ==================== 语言 ====================
            // 原版 ty_core.lua 的 语言() 反编译出来是恒等函数（多语言是空壳），
            // 所以这里不照搬那套，直接用 Android 标准的 per-app locale。
            SectionTitle("语言")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                com.cloudbox.app.common.LocaleUtil.LANGUAGES.forEach { (code, label) ->
                    Row(
                        Modifier.clickable { viewModel.saveAppLanguage(code) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.appLanguage == code,
                            onClick = { viewModel.saveAppLanguage(code) }
                        )
                        Text(label)
                    }
                }
            }
            HorizontalDivider()

            // ==================== 下载行为 ====================
            SectionTitle("下载行为")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("移动网络下载前提醒", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "在流量下点下载时先问一句，避免误触消耗流量",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.warnMobileNetwork,
                    onCheckedChange = viewModel::saveWarnMobileNetwork
                )
            }
            HorizontalDivider()

            // ==================== 界面显示（对齐原版 v1.3.4.9 自定义设置页） ====================
            SectionTitle("界面显示")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("显示文件后缀标签", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "在文件图标处叠加显示文件类型后缀",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.showFileTypeLabel,
                    onCheckedChange = viewModel::saveShowFileTypeLabel
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("显示账号切换按钮", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "关闭后首页不再显示账号入口（单账号用户可关）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.showAccountButton,
                    onCheckedChange = viewModel::saveShowAccountButton
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动加载页面剩余内容", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "列表滚动到底部时自动加载下一页，无需手动点击",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.autoLoad,
                    onCheckedChange = viewModel::saveAutoLoad
                )
            }
            HorizontalDivider()

            // ==================== 收藏夹（对齐原版 v1.3.4.9 消息设置页） ====================
            SectionTitle("收藏夹")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动检查收藏夹更新", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "定期检查收藏的文件夹是否有新文件，有更新会在收藏夹显示红点。" +
                            "收藏的文件夹过多时检查会较慢，不建议设置过短的间隔。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.autoCheckFavoritesDays > 0,
                    onCheckedChange = { on ->
                        viewModel.saveAutoCheckFavoritesDays(if (on) DEFAULT_CHECK_DAYS else 0)
                    }
                )
            }
            if (state.autoCheckFavoritesDays > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("检查间隔", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    // 原版档位：1 / 3 / 7 / 14 / 30 天
                    CHECK_INTERVAL_DAYS.forEach { d ->
                        FilterChip(
                            selected = state.autoCheckFavoritesDays == d,
                            onClick = { viewModel.saveAutoCheckFavoritesDays(d) },
                            label = { Text("${d}天") },
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
            HorizontalDivider()

            // ==================== 数据管理（对齐原版 privacy_settings 的备份/重置） ====================
            SectionTitle("数据管理")
            DataActionRow(
                title = "备份数据",
                subtitle = "把收藏夹和全部设置导出成一个 JSON 文件，" +
                    "存放在 Download/云匣备份/。不含账号密码与 Cookie（那是凭据，" +
                    "明文写进文件不安全），换机后重新登录一次即可。",
                buttonText = "备份",
                enabled = !state.dataBusy,
                onClick = viewModel::backupData
            )
            DataActionRow(
                title = "恢复数据",
                subtitle = "从备份文件恢复收藏夹与设置。会先清空现有收藏，" +
                    "所以选完文件后会让您再确认一次。",
                buttonText = "选择文件",
                enabled = !state.dataBusy,
                // 用 */* 而不是 application/json：备份文件经常在传输/网盘落盘后
                // 丢掉 MIME（变成 octet-stream 或空），只筛 json 会导致
                // 用户在文件选择器里**看不见自己的备份文件**。
                // 文件对不对由 prepareRestore 解析时判断，这里放开更实用。
                onClick = { restorePicker.launch(arrayOf("*/*")) }
            )
            DataActionRow(
                title = "清除缓存",
                subtitle = "清理上传中间文件、分卷临时文件等。" +
                    "已下载到本机的文件不受影响。",
                buttonText = "清除（${formatCacheSize(state.cacheBytes)}）",
                enabled = !state.dataBusy,
                onClick = viewModel::clearCache
            )
            DataActionRow(
                title = "重置应用",
                subtitle = "设置恢复默认、收藏夹与本地缓存清空。" +
                    "登录状态会保留，不会把您登出。",
                buttonText = "重置",
                enabled = !state.dataBusy,
                danger = true,
                onClick = { resetConfirm = true }
            )
            HorizontalDivider()

            Spacer(Modifier.height(24.dp))
            Text("云匣 v0.1.143 · 仅供个人学习使用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
        }
    }

    if (spoofDialog) {
        AlertDialog(
            onDismissRequest = { spoofDialog = false },
            title = { Text("自定义伪装后缀") },
            text = {
                Column {
                    Text(
                        "这些格式上传前会被改名为 .zip（蓝奏云按扩展名拦截），下载时自动还原。\n" +
                            "用逗号或空格分隔，不用写点号。清空则恢复默认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = spoofInput,
                        onValueChange = { spoofInput = it },
                        minLines = 2,
                        placeholder = { Text(com.cloudbox.app.common.SpoofSuffixUtil.DEFAULT_RAW) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveSpoofSuffixList(spoofInput)
                    spoofDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        spoofInput = com.cloudbox.app.common.SpoofSuffixUtil.DEFAULT_RAW
                    }) { Text("恢复默认") }
                    TextButton(onClick = { spoofDialog = false }) { Text("取消") }
                }
            }
        )
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

    // ---------- V32：恢复数据前预览（恢复是破坏性操作，必须先让用户看清内容） ----------
    state.restorePreview?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRestorePreview,
            title = { Text(if (p.isEmpty) "这份备份是空的" else "确认恢复") },
            text = {
                Column {
                    Text("这份备份包含 " +
                        "${p.favoriteCount} 个收藏、${p.settingCount} 项设置。",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "备份时间：${formatBackupTime(p.backupTime)}\n" +
                            "备份来自：云匣 v${p.backupAppVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (p.isEmpty) {
                        // 空备份：文件可能是被截断了，恢复它不会有任何收益，
                        // 只会把当前收藏清空 —— 必须用最强措辞拦住。
                        Text(
                            "⚠️ 里面既没有收藏也没有设置，恢复它只会把您现有的收藏夹清空，" +
                                "不会带回任何内容。\n\n" +
                                "这通常说明备份文件不完整（传输/同步中被截断）。" +
                                "建议取消，重新找一份完整的备份文件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            "⚠️ 恢复会清空现有收藏夹再写入备份内容，" +
                                "当前已有的收藏将无法找回。设置项按备份内容覆盖，不会动登录状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) {
                    // 空备份时把按钮文案改成"仍要清空"，让用户明确知道自己点的是什么
                    Text(if (p.isEmpty) "仍要清空" else "确认恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestorePreview) { Text("取消") }
            }
        )
    }

    // ---------- V32：重置应用二次确认 ----------
    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text("重置应用") },
            text = {
                Column {
                    Text("将清除：", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "· 全部设置项（恢复默认值）\n" +
                            "· 收藏夹（全部清空）\n" +
                            "· 域名配置改动\n" +
                            "· 本地缓存与文件列表缓存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "登录状态会保留，已下载到本机的文件不受影响。\n" +
                            "此操作不可撤销，建议先「备份数据」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    resetConfirm = false
                    viewModel.resetAppData()
                }) { Text("确认重置") }
            },
            dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text("取消") } }
        )
    }
}

/** 打开「自动检查收藏夹更新」时默认使用 7 天（原版档位之一，取中间值最不激进） */
private const val DEFAULT_CHECK_DAYS = 7

/** 原版 settings/message_settings.lua 的档位：1 / 3 / 7 / 14 / 30 天 */
private val CHECK_INTERVAL_DAYS = listOf(1, 3, 7, 14, 30)

/**
 * 数据管理区的单行：左侧标题+说明，右侧一个按钮。
 *
 * 和 [SettingRow] 的区别是右侧是个按钮而不是箭头：这一区全是**动作**
 * （备份/恢复/清除/重置），点箭头会让人以为是进子页面。
 */
@Composable
private fun DataActionRow(
    title: String,
    subtitle: String,
    buttonText: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(
            onClick = onClick,
            enabled = enabled,
            colors = if (danger) {
                androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            } else {
                androidx.compose.material3.ButtonDefaults.textButtonColors()
            }
        ) { Text(buttonText) }
    }
}

/** 缓存体积的紧凑显示（设置页里只需要看个大概量级） */
private fun formatCacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 备份时间戳转可读文本；0 表示备份文件里没写（老文件） */
private fun formatBackupTime(millis: Long): String =
    if (millis <= 0) "未知"
    else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

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

