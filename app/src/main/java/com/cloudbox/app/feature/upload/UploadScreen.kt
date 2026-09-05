package com.cloudbox.app.feature.upload

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cloudbox.app.feature.filelist.dialog.MoveFolderDialog

/**
 * 上传页（V5 交互）：SAF 选择文件 → 直接入队 WorkManager 上传（不再有暂存列表）。
 * - 目标文件夹：默认根目录，可单独切换；超 100MB 文件自动分卷（95MB/卷）。
 * - 上传中显示全局进度与当前文件；结果（含失败名单）常驻展示，"知道了"手动关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    onBack: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showFolderPicker by remember { mutableStateOf(false) }
    var targetFolderId by remember { mutableLongStateOf(-1L) }
    var targetFolderName by remember { mutableStateOf("根目录") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 选完直接按当前目标文件夹入队（spoof 开关由设置页控制，Worker 内二次校验）
            viewModel.enqueueUpload(uris, targetFolderId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("上传") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 目标文件夹选择
            OutlinedButton(
                onClick = { showFolderPicker = true },
                enabled = !state.uploading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Folder, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("上传到：$targetFolderName")
            }
            Spacer(Modifier.height(12.dp))

            Text(
                "点击下方按钮选择文件，选完自动开始上传（支持多选；超过 100MB 的文件将自动分卷）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))

            // 上传进度
            if (state.uploading) {
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.progress.toFloat() / state.total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text("${state.progress}/${state.total}  正在上传：${state.currentFile}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            // 上传结果与失败名单（多批汇总）
            state.message?.let { msg ->
                val isError = state.failedFiles.isNotEmpty()
                Text(
                    msg,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isError) {
                    Spacer(Modifier.height(4.dp))
                    state.failedFiles.take(5).forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.failedFiles.size > 5) {
                        Text("…等共 ${state.failedFiles.size} 个失败",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // V3(P3)：结果消息常驻，提供"知道了"手动关闭
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = viewModel::dismissMessage) {
                    Text("知道了")
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    enabled = !state.uploading,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.uploading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(4.dp))
                        Text("上传中…")
                    } else {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("选择文件上传")
                    }
                }
            }
        }
    }

    if (showFolderPicker) {
        MoveFolderDialog(
            onDismiss = { showFolderPicker = false },
            onConfirm = { id, name ->
                targetFolderId = id
                targetFolderName = name
                showFolderPicker = false
            }
        )
    }
}
