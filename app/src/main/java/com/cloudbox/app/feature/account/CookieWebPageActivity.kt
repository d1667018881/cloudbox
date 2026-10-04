package com.cloudbox.app.feature.account

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.cloudbox.app.core.data.remote.CookiePersistenceJar
import com.cloudbox.app.ui.theme.CloudBoxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 通用「带登录态打开网页」页（V47，2026-10-04）。
 *
 * 账号面板的 网页版 / 个人中心 / 变更手机号 / 注销账户 需要在网页里完成——
 * 变更手机号要短信验证码、注销是高危操作，App 侧不做协议复刻（原版蓝云
 * 同样跳网页）。OkHttp 的登录 Cookie 在 [CookiePersistenceJar]，系统 WebView
 * 用另一套 CookieManager，必须显式灌入，否则打开是未登录页——灌 cookie 的
 * 方式与 [com.cloudbox.app.feature.upload.WebViewUploadActivity] 同款。
 */
@AndroidEntryPoint
class CookieWebPageActivity : ComponentActivity() {

    @Inject lateinit var cookieJar: CookiePersistenceJar

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "网页"
        injectCookies(url)

        setContent {
            CloudBoxTheme {
                var progress by remember { mutableIntStateOf(0) }
                var pageTitle by remember { mutableStateOf(title) }
                Scaffold(
                    topBar = {
                        androidx.compose.foundation.layout.Column {
                            TopAppBar(
                                title = { Text(pageTitle, maxLines = 1) },
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                                    }
                                }
                            )
                            if (progress in 1..99) {
                                LinearProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        AndroidViewFactory(url, { newProgress -> progress = newProgress }) { t ->
                            pageTitle = t
                        }
                    }
                }
            }
        }
    }

    /** WebView 创建封装（Compose 嵌 WebView 最简形态）；标题/进度跟随页面走 */
    @Composable
    private fun AndroidViewFactory(
        url: String,
        onProgress: (Int) -> Unit,
        onTitle: (String) -> Unit
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            view.loadUrl(request.url.toString())
                            return true
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            onProgress(newProgress)
                        }

                        override fun onReceivedTitle(view: WebView, t: String?) {
                            onTitle(t ?: url)
                        }
                    }
                    loadUrl(url)
                }
            }
        )
    }

    /** 把登录 Cookie 灌进系统 WebView 的 CookieManager（同 WebViewUploadActivity） */
    private fun injectCookies(url: String) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        val targetHost = if (host.endsWith("woozooo.com")) "woozooo.com" else host
        cookieJar.export().forEach { line ->
            val nameValue = line.substringBefore(";").trim()
            if (nameValue.contains("=")) {
                runCatching { cm.setCookie("https://$targetHost/", nameValue) }
            }
        }
        cm.flush()
    }

    companion object {
        private const val EXTRA_URL = "extra.url"
        private const val EXTRA_TITLE = "extra.title"

        fun start(context: Context, url: String, title: String) {
            context.startActivity(
                Intent(context, CookieWebPageActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }
}
