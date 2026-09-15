package com.cloudbox.app.common

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cloudbox.app.ui.theme.CloudBoxTheme

/**
 * 程序错误页（对齐原版 `error_page.lua`）。
 *
 * 独立 Activity：崩溃时主 Activity 的导航栈可能已经处于不一致状态，
 * 直接在里面改 UI 极易二次崩溃。
 */
class ErrorActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ERROR = "extra_error"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent?.getStringExtra(EXTRA_ERROR)
            ?: CrashHandler.readPersisted(this)
            ?: "无内容"

        setContent {
            CloudBoxTheme(darkMode = "system") {
                Surface(Modifier.fillMaxSize()) {
                    ErrorPage(
                        report = report,
                        onRestart = {
                            // 重启到主入口。用包名启动器 intent，避免依赖 MainActivity 的
                            // 具体类名写死（模块重命名时不会静默失效）。
                            val i = packageManager.getLaunchIntentForPackage(packageName)
                            if (i != null) {
                                i.addFlags(
                                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                )
                                startActivity(i)
                            }
                            finish()
                        },
                        onClose = { finish() },
                        onClear = { CrashHandler.clear(this) }
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ErrorPage(
    report: String,
    onRestart: () -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("程序错误") }) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("程序遇到了一个错误", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "可以复制下面的信息反馈给维护者；重启后 App 可继续使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Card(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.fillMaxSize().padding(12.dp)) {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onRestart) { Text("重启") }
                OutlinedButton(onClick = onClose) { Text("关闭") }
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("崩溃日志", report))
                    copied = true
                }) { Text(if (copied) "已复制" else "复制") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "复制后可粘贴到任何地方保存；点「重启」会清理本次记录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
