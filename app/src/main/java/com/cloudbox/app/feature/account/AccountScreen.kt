package com.cloudbox.app.feature.account

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

private enum class AccountDialog { PASSWORD, EXTERNAL_LINK, PUBLISHER, SHARE_CODE }

/**
 * 管理账号面板（V47 重写，对齐原版蓝云「管理账号」弹窗截图，2026-10-03）。
 *
 * 结构：头部（用户 ID 粗体 + 绑定手机灰色小字 + 会员等级徽章）+ 9 项功能列表：
 * 个人中心 / 网页版 / 修改密码 / 外链设置 / 昵称设置 / 个人分享链 / 变更手机号 /
 * 注销账户 / 退出登录。
 *
 * 前情：旧版是「信息展示页」——个人分享链直接展示 [UserProfile.shareLink]
 * 原文，页面改版后解析正则抓到导航区整段 HTML，TA 真机看到一坨网页源码。
 * 新版改为蓝云同款「入口列表」；shareLink 解析已加污染清洗（含 '<' 时提取
 * http 链接，提不到显示"未获取"），在「个人分享链」对话框里展示与复制。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit = onBack,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<AccountDialog?>(null) }
    // 退出登录确认（与 MainScreen 抽屉退出同款：不可撤销，先问一句）
    var confirmLogout by remember { mutableStateOf(false) }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    // VM 消息 → Snackbar
    LaunchedEffect(Unit) {
        viewModel.uiState.collect { st ->
            st.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
        }
    }
    // 退出登录完成 → 切登录页（先清 Cookie 再导航，见 VM.logout 注释）
    LaunchedEffect(Unit) {
        viewModel.loggedOut.collect { onLogout() }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("将清除本机保存的登录凭证与密码（云端数据不受影响）。确定退出？") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; viewModel.logout() }) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("管理账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            val p = state.profile
            // ── 头部：ID 粗体 + 手机灰 + 会员徽章（对齐蓝云截图） ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        p?.userName ?: "…",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    p?.phone?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            it,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                p?.level?.takeIf { it.isNotBlank() }?.let { lv ->
                    Text(
                        lv,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            HorizontalDivider()

            // ── 9 项功能列表（对齐蓝云） ──
            AccountItem("个人中心", Icons.Filled.Person) {
                CookieWebPageActivity.start(context, viewModel.accountCenterUrl(), "个人中心")
            }
            AccountItem("网页版", Icons.Filled.Public) {
                CookieWebPageActivity.start(context, viewModel.webDiskUrl(), "网页版")
            }
            AccountItem("修改密码", Icons.Filled.Password) { dialog = AccountDialog.PASSWORD }
            AccountItem("外链设置", Icons.Filled.Link) { dialog = AccountDialog.EXTERNAL_LINK }
            AccountItem("昵称设置", Icons.Filled.Person) {
                // task=15：显示发布者开关 + 昵称输入（同一对话框，语义覆盖）
                dialog = AccountDialog.PUBLISHER
            }
            AccountItem("个人分享链", Icons.Filled.Share) { dialog = AccountDialog.SHARE_CODE }
            AccountItem("变更手机号", Icons.Filled.Smartphone) {
                // 需短信验证，App 侧做不了 → 打开官方账户页（页内有入口）
                CookieWebPageActivity.start(context, viewModel.accountSecurityUrl(), "变更手机号")
            }
            AccountItem("注销账户", Icons.Filled.PersonOff) {
                // 高危操作 + 短信验证 → 官方账户页
                CookieWebPageActivity.start(context, viewModel.accountSecurityUrl(), "注销账户")
            }
            AccountItem(
                "退出登录",
                Icons.AutoMirrored.Filled.ExitToApp,
                danger = true
            ) { confirmLogout = true }
        }
    }

    when (dialog) {
        AccountDialog.PASSWORD -> PasswordDialog(
            submitting = state.submitting,
            onDismiss = { dialog = null },
            onConfirm = { old, new -> dialog = null; viewModel.changePassword(old, new) }
        )
        AccountDialog.EXTERNAL_LINK -> ExternalLinkDialog(
            submitting = state.submitting,
            initialTitle = state.profile?.externalLinkTitle.orEmpty(),
            initialSummary = state.profile?.externalLinkSummary.orEmpty(),
            onDismiss = { dialog = null },
            onConfirm = { t, s -> dialog = null; viewModel.setExternalLink(t, s) }
        )
        AccountDialog.PUBLISHER -> PublisherDialog(
            submitting = state.submitting,
            initialShow = state.profile?.publisherVisible ?: false,
            initialName = state.profile?.publisherName.orEmpty(),
            onDismiss = { dialog = null },
            onConfirm = { show, name -> dialog = null; viewModel.setPublisher(show, name) }
        )
        AccountDialog.SHARE_CODE -> ShareLinkDialog(
            submitting = state.submitting,
            shareLink = state.profile?.shareLink,
            shareLinkCode = state.profile?.shareLinkCode,
            onDismiss = { dialog = null },
            onCopyLink = { link, code ->
                val text = if (code.isNullOrBlank()) link
                else "$link?pass=$code"
                copyToClipboard("分享链", text)
                viewModel.postMessage("已复制分享链")
            },
            onConfirm = { enable, code -> dialog = null; viewModel.setPersonalLinkCode(enable, code) }
        )
        null -> {}
    }
}

/** 功能列表行（左图标 + 文字，无右箭头——对齐蓝云截图形态） */
@Composable
private fun AccountItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, null,
            Modifier.size(22.dp),
            tint = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun PasswordDialog(
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column {
                OutlinedTextField(
                    value = oldPwd,
                    onValueChange = { oldPwd = it },
                    label = { Text("旧密码") },
                    singleLine = true,
                    // V41（N8）：登录页有遮罩、这里却没有 —— 改密对话框输密码时
                    // 明文可见（肩窥/录屏/截图面）。对齐 LoginScreen 的处理。
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPwd,
                    onValueChange = { newPwd = it },
                    label = { Text("新密码（至少 6 位）") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && oldPwd.isNotBlank() && newPwd.isNotBlank(),
                onClick = { onConfirm(oldPwd, newPwd) }
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ExternalLinkDialog(
    submitting: Boolean,
    initialTitle: String,
    initialSummary: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var summary by remember { mutableStateOf(initialSummary) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("外链设置") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("简介") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && title.isNotBlank(),
                onClick = { onConfirm(title, summary) }
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PublisherDialog(
    submitting: Boolean,
    initialShow: Boolean,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, String) -> Unit
) {
    var show by remember { mutableStateOf(initialShow) }
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("昵称设置") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("对外显示发布者")
                    Switch(checked = show, onCheckedChange = { show = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("昵称（发布者名称）") },
                    singleLine = true,
                    enabled = show,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && (!show || name.isNotBlank()),
                onClick = { onConfirm(show, name) }
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 个人分享链对话框（V47 增强）：顶部展示分享链 URL（解析清洗后的）+ 提取码 +
 * 复制按钮；下方仍是访问码开关（task=7）。
 */
@Composable
private fun ShareLinkDialog(
    submitting: Boolean,
    shareLink: String?,
    shareLinkCode: String?,
    onDismiss: () -> Unit,
    onCopyLink: (String, String?) -> Unit,
    onConfirm: (Boolean, String) -> Unit
) {
    // V48（TA 真机实锤"没有启用分享码但显示开启"）：初始值读**页面解析出的
    // 实际状态**——个人分享链有密码（shareLinkCode 非空）= 访问码已启用。
    // 此前写死 true，纯 UI 假状态。
    var enable by remember { mutableStateOf(!shareLinkCode.isNullOrBlank()) }
    var code by remember { mutableStateOf(shareLinkCode ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("个人分享链") },
        text = {
            Column {
                if (!shareLink.isNullOrBlank()) {
                    Text(
                        shareLink,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!shareLinkCode.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "提取码：$shareLinkCode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onCopyLink(shareLink, shareLinkCode) }) {
                        Text("复制链接")
                    }
                } else {
                    Text(
                        "未获取到分享链（账户页解析未命中）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("启用访问码")
                    Switch(checked = enable, onCheckedChange = { enable = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("访问码") },
                    singleLine = true,
                    enabled = enable,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && (!enable || code.isNotBlank()),
                onClick = { onConfirm(enable, code) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
