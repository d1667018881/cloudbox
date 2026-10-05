package com.cloudbox.app.core.data.repository

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 直链 acw 挑战的 WebView 执行桥。
 *
 * ─────────────────────────────────────────────────────────────
 * 为什么需要它（2026-09 实测，v1.3.4.9 对照）
 * ─────────────────────────────────────────────────────────────
 * 蓝奏云的直链域名（*.dmpdmp.com 等）在风控命中时会返回一个**混淆 JS 挑战页**：
 *
 *   <html><script>var arg1='2AF103EC…';(function(a,c){var G=a0j,d=a();while(!![])…})
 *   (a0i,0x760bf)…</script></html>
 *
 * 它跟分享页那种「arg1 常量表 + 固定密钥 XOR」的挑战**不是一回事**：
 *   - 分享页挑战：[AcwScV2.compute] 静态算得出来，纯 OkHttp 就能过。
 *   - 直链挑战：字符串表 + 控制流平坦化 + 自校验，必须在真 JS 引擎里跑。
 *     实测同一 session 反复请求 3 次，每次返回新的 arg1，CookieJar 里始终没有
 *     acw_sc__v2 → 说明服务端只认「JS 执行完后的那个 cookie」。
 *
 * 原版 v1.3.4.9 的同类处理：home_func.lua 里识别到 `var arg1=` 后走
 * 「带 header 重载」，即开一个隐藏 WebView 把页面加载完，让系统执行 JS。
 * 本类即该思路的实现。
 *
 * ─────────────────────────────────────────────────────────────
 * 设计约束
 * ─────────────────────────────────────────────────────────────
 * - 必须在主线程建/销毁 WebView（Android 硬性要求）。
 * - 必须设置 WebViewClient，否则 loadUrl 会跳系统浏览器。
 * - 域名通配：不同用户的文件落在不同子域（u556911/u123456…），不能写死。
 * - 超时 12s：WebView 首帧在低端机上可能 3-5s，压太紧会误判失败。
 * - 全程 runCatching：失败只意味着「这次过不了挑战」，绝不能让下载流程崩。
 *
 * ⚠️ 本类持有 Activity 无关的 applicationContext，不泄漏。
 *    并发不设锁：同时过两个挑战会建两个 WebView（略浪费但各自独立、
 *    CookieManager 全局可见，不损坏正确性）。V40 复审更正：本文件从未有
 *    synchronized——早期注释把它写成"已有"与实现不符。
 */
object DirectLinkWebViewBridge {

    /** 挑战 JS 执行完通常会立刻写 cookie；12s 覆盖低端机首帧 + JS 执行 */
    private const val TIMEOUT_MS = 12_000L

    /** 轮询兜底的间隔与次数（部分机型 onPageFinished 不回调） */
    private const val POLL_INTERVAL_MS = 500L
    private const val POLL_MAX_TRIES = 24

    /** 验证页自动通过超时：acw 挑战 + 3 轮 down_r（每轮失败 reload 1s）+ 裕量 */
    private const val VERIFIED_TIMEOUT_MS = 25_000L

    /**
     * 同步等待版：给非 suspend 调用链（[DirectLinkRepositoryImpl.ensureDownloadable]）用。
     *
     * ⚠️ 内部会阻塞当前线程直到超时或拿到 cookie。**禁止在主线程调用**——
     * 仓库层全部跑在 Dispatchers.IO，调用点是安全的。
     */
    fun acquireCookieSync(url: String, referer: String): String? {
        return kotlinx.coroutines.runBlocking { acquireCookie(url, referer) }
    }

    /**
     * V48（2026-10-05）：**验证页自动通过**，返回「立即下载」的真实下载地址。
     *
     * 背景：蓝奏云 10 月起在直链域上了「网络异常验证」（页面文案：系统发现
     * 您的网络异常，需要验证后下载文件）。实测证据链（2026-10-05，curl +
     * 真浏览器双环境对照）：
     * - 验证页 = 三个按钮 down_r(1)/down_r(2)/down_r(3)，点对即过（实测
     *   两轮 el=2 均直接通过），点错页面 reload 换新 sign
     * - OkHttp/curl 无论怎么复刻头（新 sign + Origin + X-Requested-With +
     *   Accept + HTTP/2 + 全套 cookie）POST ajax.php 一律被拒返回 HTML
     * - Chrome 内核（WebView/agent-browser）稳定通过
     * → 服务端校验的是客户端 JS 执行环境/指纹，非头部可复刻
     *
     * 因此 OkHttp 的 resolveVerifiedUrl 失败后，本函数开隐藏 WebView 走
     * 完整浏览器路径：load 直链 → JS 自动过 acw 挑战 → 验证页 onPageFinished
     * 注入 down_r(el)（1→2→3 轮换，失败 reload 后自动点下一个）→ 验证成功
     * 页面出现 `#go a`（立即下载）→ 取其 href 即真链（自带签名无需 cookie，
     * CDN 实测裸请求可下）。
     *
     * 返回的 URL 交给 ensureDownloadable 再 probe（302→CDN finalize）。
     */
    fun acquireVerifiedUrlSync(url: String, referer: String): String? {
        return kotlinx.coroutines.runBlocking { acquireVerifiedUrl(url, referer) }
    }

    suspend fun acquireVerifiedUrl(url: String, referer: String): String? =
        withTimeoutOrNull(VERIFIED_TIMEOUT_MS) { loadAndVerify(url, referer) }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadAndVerify(url: String, referer: String): String? =
        withContext(Dispatchers.Main) {
            try {
                suspendCancellableCoroutine { cont ->
                    val ctx = currentApplication() ?: run {
                        cont.resume(null); return@suspendCancellableCoroutine
                    }
                    var settled = false
                    var clicks = 0
                    var lastClickMs = 0L
                    val web = WebView(ctx)
                    val handler = Handler(Looper.getMainLooper())

                    fun finish(value: String?) {
                        if (settled) return
                        settled = true
                        handler.removeCallbacksAndMessages(null)
                        runCatching { web.stopLoading() }
                        runCatching { web.destroy() }
                        cont.resume(value)
                    }

                    // 单一自续轮询（V48c 修正 V48b 致命 bug）：
                    // 每 1s 查「立即下载」链接；没有则距上次点击 ≥2.5s 且次数
                    // 未用尽时点下一个 down_r(el)（1→2→3 循环，最多 6 次）。
                    //
                    // ⚠️ V48b 的 bug：验证成功路径是 jQuery 把 <a> 注入 #go，
                    //    **无任何页面跳转/回调**——只在 onPageFinished 查一次
                    //    链接、点击后无后续轮询 → 链接永远没人读 → 25s 超时
                    //    报"自动通过失败"（TA 真机 v0.1.200 复现）。
                    //    纯轮询统一覆盖全部路径：acw 挑战 reload、验证失败
                    //    reload、成功注入、以及 DownloadListener 直接触发。
                    val tick = object : Runnable {
                        override fun run() {
                            if (settled) return
                            web.evaluateJavascript(
                                "(function(){var a=document.querySelector('#go a');return (a&&a.href)?a.href:null})()"
                            ) { res ->
                                if (settled) return@evaluateJavascript
                                val href = res?.trim('"')
                                if (!href.isNullOrEmpty() && href != "null" && href.startsWith("http")) {
                                    finish(href); return@evaluateJavascript
                                }
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastClickMs >= 2500 && clicks < 6) {
                                    clicks++
                                    lastClickMs = now
                                    val el = ((clicks - 1) % 3) + 1
                                    web.evaluateJavascript(
                                        "typeof down_r==='function'?(down_r($el),'ok'):'no'"
                                    ) { }
                                }
                                handler.postDelayed(this, 1000)
                            }
                        }
                    }

                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.settings.userAgentString = DESKTOP_UA

                    web.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false
                    }
                    // 兜底捕获：万一流程以「直接开始下载」收场（WebView 对文件
                    // URL 触发 onDownloadStart），从这拿最终地址
                    web.setDownloadListener { dlUrl, _, _, _, _ ->
                        if (dlUrl.startsWith("http")) finish(dlUrl)
                    }

                    web.loadUrl(url, mapOf("Referer" to referer))
                    handler.postDelayed(tick, 1500)

                    cont.invokeOnCancellation { handler.post { finish(null) } }
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * 打开隐藏 WebView 加载 [url]，等 acw_sc__v2 出现后返回 `name=value`。
     * 超时或异常返回 null。
     */
    suspend fun acquireCookie(url: String, referer: String): String? =
        withTimeoutOrNull(TIMEOUT_MS) { loadAndWait(url, referer) }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadAndWait(url: String, referer: String): String? =
        withContext(Dispatchers.Main) {
            try {
                suspendCancellableCoroutine { cont ->
                    val ctx = currentApplication() ?: run {
                        cont.resume(null); return@suspendCancellableCoroutine
                    }

                    var settled = false
                    val web = WebView(ctx)
                    val handler = Handler(Looper.getMainLooper())

                    fun finish(value: String?) {
                        if (settled) return
                        settled = true
                        handler.removeCallbacksAndMessages(null)
                        // ⚠️ 必须在 resume 之前/之后都保证 WebView 被销毁，
                        //    但绝不能在 suspendCancellableCoroutine **返回后**统一销毁——
                        //    那样会立刻销毁、挑战 JS 根本没机会执行。
                        runCatching { web.stopLoading() }
                        runCatching { web.destroy() }
                        cont.resume(value)
                    }

                    fun readCookie(): String? {
                        val raw = runCatching {
                            CookieManager.getInstance().getCookie(hostOf(url) ?: "")
                        }.getOrNull().orEmpty()
                        val m = Regex("""(?:^|;\s*)(acw_sc__v2=[^;]+)""").find(raw)
                        return m?.groupValues?.get(1)
                    }

                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.settings.userAgentString = DESKTOP_UA

                    web.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false // 一律在 WebView 内加载，别跳系统浏览器

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)
                            // 页面加载完 → 挑战 JS 一般已执行 → 读 cookie。
                            // evaluateJavascript 回调时机不稳定，两条路径都试。
                            readCookie()?.let { finish(it); return }
                            view?.evaluateJavascript("document.cookie") {
                                readCookie()?.let { ck -> finish(ck) }
                            }
                        }

                        override fun onPageCommitVisible(view: WebView?, pageUrl: String?) {
                            super.onPageCommitVisible(view, pageUrl)
                            readCookie()?.let { finish(it) }
                        }
                    }

                    web.loadUrl(url, mapOf("Referer" to referer))

                    cont.invokeOnCancellation { handler.post { finish(null) } }

                    // 兜底轮询：部分机型 onPageFinished 不回调，直接盯 cookie
                    handler.postDelayed(object : Runnable {
                        var tries = 0
                        override fun run() {
                            if (settled) return
                            readCookie()?.let { finish(it); return }
                            if (++tries > POLL_MAX_TRIES) { finish(null); return }
                            handler.postDelayed(this, POLL_INTERVAL_MS)
                        }
                    }, POLL_INTERVAL_MS)
                }
            } catch (t: Throwable) {
                // 任何 WebView 层异常（无 WebView 实现、主线程被占用…）都不该冒泡，
                // 上层按「过不了挑战」处理即可。
                null
            }
        }

    // ==================== helpers ====================

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 取 host：CookieManager 需要能匹配的域（不带 scheme/path） */
    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 拿 Application 实例。
     *
     * 用反射读 ActivityThread 是为了让本 object 保持无参、免 DI —— 这个桥只在
     * 「挑战页」这条罕见路径上用到，为它往 Hilt 图里加节点得不偿失。
     * 失败返回 null，调用方按「过不了挑战」处理。
     */
    private fun currentApplication(): android.content.Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val m = at.getDeclaredMethod("currentApplication")
        m.isAccessible = true
        m.invoke(null) as? android.content.Context
    }.getOrNull()
}
