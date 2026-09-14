package com.cloudbox.app.feature.filelist.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cloudbox.app.feature.filelist.FileListViewModel

/**
 * 目录选择对话框（通用）。
 *
 * 两个使用场景共用：
 * - 文件移动（原用途："移动到"）
 * - **上传目标目录选择**（"上传到"）—— 分享文件进来、或想传去非当前目录时用
 *
 * 目标列表来自 task=19（全部文件夹平铺）；只支持文件移动（文件夹移动官方无接口）。
 *
 * @param title 对话框标题。默认"移动到"；上传场景传"上传到"。
 * @param rootLabel 根目录那一行的显示名。上传场景可传"根目录（我的文件）"。
 */
@Composable
fun MoveFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
    title: String = "移动到",
    rootLabel: String = "根目录",
    viewModel: FileListViewModel = hiltViewModel()
) {
    var folders by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }

    LaunchedEffect(Unit) {
        folders = viewModel.fileRepository.getAllFolders().getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val list = folders
            if (list == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        rootLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(-1, rootLabel) }
                            .padding(vertical = 8.dp)
                    )
                    HorizontalDivider()
                    LazyColumn(Modifier.height(280.dp)) {
                        items(list, key = { it.first }) { (id, name) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onConfirm(id, name) }
                                    .padding(vertical = 8.dp)
                            ) {
                                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(8.dp))
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
