package com.cloudbox.app.feature.account

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private enum class AccountDialog { PASSWORD, EXTERNAL_LINK, PUBLISHER, SHARE_CODE }

/**
 * 账号面板（对齐原版 `account.lua` 的「管理账号」弹窗内容）。
 *
 * 展示：显示名 / 用户名 / 文件数 / 累计下载 / 个人分享链(+提取码) / 外链标题简介 / 发布者。
 * 操作：修改密码、外链设置、显示发布者、个人分享链 —— 对应 task=8/10/15/7。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<AccountDialog?>(null) }

    // ⚠️ 局部函数必须在引用它的 lambda 之前声明（词法作用域）
    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

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
                title = { Text("账号信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
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
            when {
                state.loading -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }

                state.error != null -> {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = viewModel::refresh) { Text("重试") }
                }

                else -> {
                    val p = state.profile ?: return@Column

                    Text(
                        p.publisherName ?: p.displayName ?: p.userName ?: "云匣用户",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    if (!p.userName.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            p.userName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            InfoLine("文件数", p.fileCount ?: "—")
                            InfoLine("累计下载", p.downloadCount ?: "—")
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("个人分享链", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            if (!p.shareLink.isNullOrBlank()) {
                                Text(
                                    p.shareLink,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (!p.shareLinkCode.isNullOrBlank()) {
                                    Text(
                                        "提取码：${p.shareLinkCode}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(onClick = {
                                    val text = buildString {
                                        append(p.shareLink)
                                        if (!p.shareLinkCode.isNullOrBlank()) append("?pass=${p.shareLinkCode}")
                                    }
                                    copyToClipboard("分享链", text)
                                }) { Text("复制链接") }
                            } else {
                                Text(
                                    "未获取到",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("外链（个人主页）", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            InfoLine("标题", p.externalLinkTitle ?: "—")
                            InfoLine("简介", p.externalLinkSummary ?: "—")
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("显示发布者", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            InfoLine("状态", if (p.publisherVisible) "显示" else "隐藏")
                            InfoLine("昵称", p.publisherName ?: "—")
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text("账号设置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { dialog = AccountDialog.PASSWORD },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("修改密码") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { dialog = AccountDialog.EXTERNAL_LINK },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("外链设置") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { dialog = AccountDialog.PUBLISHER },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("显示发布者") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { dialog = AccountDialog.SHARE_CODE },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("个人分享链访问码") }
                }
            }
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
        AccountDialog.SHARE_CODE -> ShareCodeDialog(
            submitting = state.submitting,
            onDismiss = { dialog = null },
            onConfirm = { enable, code -> dialog = null; viewModel.setPersonalLinkCode(enable, code) }
        )
        null -> {}
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
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
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPwd,
                    onValueChange = { newPwd = it },
                    label = { Text("新密码（至少 6 位）") },
                    singleLine = true,
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
        title = { Text("显示发布者") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("显示发布者")
                    Switch(checked = show, onCheckedChange = { show = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("发布者昵称") },
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

@Composable
private fun ShareCodeDialog(
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, String) -> Unit
) {
    var enable by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("个人分享链访问码") },
        text = {
            Column {
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
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
