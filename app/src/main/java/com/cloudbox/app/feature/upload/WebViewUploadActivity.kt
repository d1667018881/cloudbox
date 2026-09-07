package com.cloudbox.app.feature.upload

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.core.data.remote.CookiePersistenceJar
import com.cloudbox.app.core.domain.repository.UploadRepository
import com.cloudbox.app.ui.theme.CloudBoxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 官方网页上传通道（兜底）。
 *
 * 为什么需要它：原版 App（蓝云）并不自己拼 multipart，而是 WebView 直接打开
 * 蓝奏云官方上传页，由网页 JS 处理分块、签名与风控——这是它能稳定上传的真正原因。
 * 逆向 55 个 lua 模块 + Http.java 全部调用点确认：原版**没有任何** multipart 上传调用，
 * 只有设置项 `设置.upload_url = "/html5up.php"`（给网页上传页用的）。
 *
 * 因此当原生直传被风控或协议变更挡住时，本 Activity 提供与原版等价的能力：
 * 用已登录的 Cookie 打开官方文件页，用户点网页的"上传"按钮即可。
 *
 * 实现要点：
 * - Cookie 注入：OkHttp 的 Cookie 存在我们自己的 CookiePersistenceJar 里，
 *   系统 WebView 用的是另一套 CookieManager，必须显式灌进去，否则打开是未登录页。
 * - 桌面 UA：手机 UA 下蓝奏云会切到精简页，没有 HTML5 上传控件。
 * - 文件选择：网页 <input type=file> 会回调 onShowFileChooser，需桥接到 SAF。
 */
@AndroidEntryPoint
class WebViewUploadActivity : ComponentActivity() {

    @Inject lateinit var uploadRepository: UploadRepository
    @Inject lateinit var cookieJar: CookiePersistenceJar

    /** 网页 <input type=file> 的回调（Compose 无法直接接收，暂存为字段） */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val REQUEST_SELECT_FILE = 1001

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folderId = intent.getLongExtra(EXTRA_FOLDER_ID, -1L)
        val url = uploadRepository.uploadPageUrl(folderId)
        injectCookies(url)

        setContent {
            CloudBoxTheme {
                var progress by remember { mutableIntStateOf(0) }
                var title by remember { mutableStateOf("网页上传") }
                Scaffold(
                    topBar = {
                        Column {
                            TopAppBar(
                                title = { Text(title) },
                                navigationIcon = {
                                    IconButton(onClick = {
                                        setResult(RESULT_OK)
                                        finish()
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                                    }
                                }
                            )
                            if (progress in 1..99) {
                                // 注意：material3 1.3.0（BOM 2024.09.03）的签名是
                                // progress: Float，1.7.0 才改成 () -> Float 的 lambda 版。
                                // 本项目锁的是前者，写 lambda 会直接编译不过。
                                LinearProgressIndicator(
                                    progress = progress / 100f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                ) { padding ->
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    // 桌面 UA：拿到完整上传页（HTML5 分块上传控件）
                                    userAgentString = AppConstants.DESKTOP_UA
                                    allowFileAccess = true
                                    allowContentAccess = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    @Suppress("DEPRECATION")
                                    allowUniversalAccessFromFileURLs = false
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        title = view?.title ?: "网页上传"
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }

                                    override fun onShowFileChooser(
                                        webView: WebView?,
                                        filePathCallback: ValueCallback<Array<Uri>>?,
                                        fileChooserParams: FileChooserParams?
                                    ): Boolean {
                                        this@WebViewUploadActivity.filePathCallback = filePathCallback
                                        val intent = fileChooserParams?.createIntent()
                                            ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                                type = "*/*"
                                                addCategory(Intent.CATEGORY_OPENABLE)
                                            }
                                        return try {
                                            startActivityForResult(intent, REQUEST_SELECT_FILE)
                                            true
                                        } catch (_: Exception) {
                                            this@WebViewUploadActivity.filePathCallback = null
                                            false
                                        }
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(padding)
                    )
                }
            }
        }
    }

    @Deprecated("SAF 回调：沿用 onActivityResult 以兼容 WebChromeClient 的文件选择协议")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SELECT_FILE) return
        val uris: Array<Uri>? = if (resultCode == Activity.RESULT_OK) {
            data?.clipData?.let { clip ->
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            } ?: data?.data?.let { arrayOf(it) }
        } else null
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    /**
     * 把登录凭证灌进系统 WebView 的 CookieManager。
     * setCookie 接受 "name=value" 形式，故从 jar 导出串里截取第一段。
     */
    private fun injectCookies(url: String) {
        runCatching {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            cookieJar.export()
                .map { it.substringBefore(';').trim() }
                .filter { it.contains('=') }
                .forEach { manager.setCookie(url, it) }
            manager.flush()
        }
    }

    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
        const val RESULT_NEED_REFRESH = 2001
    }
}
