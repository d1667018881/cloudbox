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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cloudbox.app.feature.filelist.dialog.MoveFolderDialog

/**
 * 上传页：选择文件（SAF）→ 目标文件夹 → 批量上传（WorkManager 后台执行，显示进度）。
 * 超过 100MB 的文件自动分卷（95MB/卷）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    onBack: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showFolderPicker by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addFiles(uris) }

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
        ) {
            // 目标文件夹选择
            OutlinedButton(
                onClick = { showFolderPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Folder, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("上传到：${state.targetFolderName}")
            }
            Spacer(Modifier.height(12.dp))

            // 文件列表
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.selectedFiles) { path ->
                    val name = path.substringAfterLast('/')
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { viewModel.removeFile(path) }, enabled = !state.uploading) {
                            Icon(Icons.Filled.Close, "移除", Modifier.size(18.dp))
                        }
                    }
                }
            }

            state.oversizeHint?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

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
                // P3(V3)：结果消息常驻，提供"知道了"手动关闭（dismissMessage 的 UI 入口）
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = viewModel::dismissMessage) {
                    Text("知道了")
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    enabled = !state.uploading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("选择文件")
                }
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = viewModel::startUpload,
                    enabled = state.selectedFiles.isNotEmpty() && !state.uploading,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.uploading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("开始上传")
                    }
                }
            }
        }
    }

    if (showFolderPicker) {
        MoveFolderDialog(
            onDismiss = { showFolderPicker = false },
            onConfirm = { id, name ->
                viewModel.setTargetFolder(id, name)
                showFolderPicker = false
            }
        )
    }
}
