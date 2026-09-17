package com.cloudbox.app.feature.upload

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.cloudbox.app.common.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「从已安装应用上传」选择器。
 *
 * 用户在这里挑选要上传的 App，确认后由调用方把它的 APK 路径
 * 交给常规上传链路（等价于用户先导出 APK 再选文件，但省掉了导出那一步）。
 *
 * ─────────────────────────────────────────────────────────────
 * 两个刻意的设计
 * ─────────────────────────────────────────────────────────────
 * 1. **枚举放在协程里做，且带"加载中"态**。`getInstalledPackages(0)`
 *    在装有几百个应用的机器上要几百毫秒到一两秒，在主线程做会卡帧。
 *    原版也是开线程做的（`thread(获取应用线程, …)`）。
 *
 * 2. **系统应用默认不显示**，对应原版 `show_system_app` 开关
 *    （ty_core.lua:709，默认 false）。系统应用用户基本不会去备份，
 *    而且数量多（动辄上百个）会把常用的第三方应用挤下去。
 *    给一个开关让需要的人自己打开。
 */
@Composable
fun InstalledAppPickerDialog(
    onDismiss: () -> Unit,
    onPick: (InstalledApps.AppEntry) -> Unit
) {
    val context = LocalContext.current
    var includeSystem by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<InstalledApps.AppEntry>>(emptyList()) }

    // includeSystem 变化时重新枚举（原版也是切换开关就重扫一遍）
    LaunchedEffect(includeSystem) {
        loading = true
        apps = withContext(Dispatchers.IO) {
            InstalledApps.list(context, includeSystem)
        }
        loading = false
    }

    val filtered = remember(apps, keyword) {
        val k = keyword.trim()
        if (k.isEmpty()) apps
        else apps.filter {
            it.label.contains(k, ignoreCase = true) ||
                it.packageName.contains(k, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从已安装应用上传") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "选中即上传该应用的安装包（APK），不用先导出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                // 系统应用开关：不打开时列表里只有第三方应用
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("显示系统应用", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "系统自带应用平时用不到，默认隐藏",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = includeSystem, onCheckedChange = { includeSystem = it })
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("搜索应用名或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                when {
                    loading -> Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(8.dp))
                            Text("正在读取应用列表…", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    filtered.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (apps.isEmpty()) {
                                "没有可上传的应用。\n若列表明显偏少，可能是系统限制了应用可见性。"
                            } else {
                                "没有匹配「$keyword」的应用"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppRow(app = app, onClick = { onPick(app) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun AppRow(app: InstalledApps.AppEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    // 图标逐行取：getApplicationIcon 有磁盘/资源开销，缓存在 remember 里避免重组重取
    val icon = remember(app.packageName) {
        InstalledApps.iconOf(context, app)?.toBitmap(96, 96)?.asImageBitmap()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            // 取不到图标时占位，避免文字对齐参差
            Spacer(Modifier.size(40.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            app.sizeText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
