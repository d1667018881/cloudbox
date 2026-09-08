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
import com.cloudbox.app.core.domain.repository.UploadProbeResult
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
    val snackbar = remember { SnackbarHostState() }
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
            // 上传通道：默认走官方网页。原生直传是逆向出来的协议，
            // 蓝奏云一改版就容易出现「显示成功但文件没上去」的假成功。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("上传改用官方网页通道（临时兜底）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "默认关闭：App 自己拼 multipart 直传（选完文件自动传）。" +
                            "若蓝奏云改版导致直传失效，打开它改用官方网页上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.preferWebUpload, onCheckedChange = viewModel::savePreferWebUpload)
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
            state.probeResult?.let {
                UploadProbeDialog(it, onDismiss = viewModel::dismissProbe)
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

/**
 * 上传自检结果弹窗。
 *
 * 展示 HTTP 码 / 实际请求地址 / 凭证状态 / **服务端原始回包**，
 * 并提供"复制"——排障时把这四行发出来，就能判断是没登录、参数不对还是端点失效。
 */
@Composable
private fun UploadProbeDialog(result: UploadProbeResult, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上传自检结果") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("HTTP ${result.httpCode}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text("请求地址：${result.requestUrl}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (result.hasCredential) "登录凭证：已检测到"
                    else "登录凭证：缺失 —— 请先退出重新登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.hasCredential) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(10.dp))
                Text("服务端原始回包：", style = MaterialTheme.typography.labelLarge)
                Text(result.rawBody, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "probe",
                        "HTTP ${result.httpCode}\n${result.requestUrl}\n"
                            + "credential=${result.hasCredential}\n${result.rawBody}"
                    )
                )
                onDismiss()
            }) { Text("复制") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
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
