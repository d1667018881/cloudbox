package com.cloudbox.app.core.data.repository

import com.cloudbox.app.common.ApiError
import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.common.DomainUtils
import com.cloudbox.app.common.HtmlExtractor
import com.cloudbox.app.core.data.local.db.AppDatabase
import com.cloudbox.app.core.data.local.db.DirectLinkEntity
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.data.remote.LanzouApiClient
import com.cloudbox.app.core.domain.model.DirectLink
import com.cloudbox.app.core.domain.repository.DirectLinkRepository
import com.cloudbox.app.core.domain.repository.ERR_FOLDER_LINK
import com.cloudbox.app.core.domain.repository.ResolveFolderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 直链解析仓库实现（2026-09 按线上实测协议重写）。
 *
 * ─────────────────────────────────────────────────────────────
 * ⚠️ 旧实现为什么必挂（改动依据，全部经抓包实证）
 * ─────────────────────────────────────────────────────────────
 * 旧流程：GET 分享页 → 正则抠 sign → POST ajaxm.php{action,sign,file_id,p,kd,ves}。
 * 蓝奏云 2025-2026 改版后这套已全线失效：
 *   1) 分享页不再含 sign，只给一个 iframe：<iframe class="ifr2" src="/fn?…">，
 *      sign 在 iframe 页里且字段名是 wp_sign（不是 sign）；
 *   2) 直链端点从 ajaxm.php 换成 **ajaxfile.php?file=<fid>**，
 *      并新增 websignkey / signs / websign 三个必带校验字段；
 *   3) fid 在页面里写作 `var fid = 96810913;`，旧正则抠 data-id="…" 抠不到；
 *   4) 直链响应里 url 以 '?' 开头（"?A2VUags6…"），拼接仍是 dom + "/file/" + url；
 *   5) inf 正常时是数字 0 而非文件名 —— 文件名改从 <title> 取。
 *
 * 现行流程（实测跑通，样本 https://www.lanzoui.com/i1evj0klyr0d）：
 *   1. GET 分享页（桌面 UA + Referer），命中 acw_sc__v2 挑战则解挑战后重取
 *   2. 分享页抠 <title>（文件名） + var fid（文件 id） + iframe src
 *   3. GET iframe 页（/fn?…），抠 var wp_sign / var ajaxdata / var kdns
 *   4. POST ajaxfile.php?file=<fid>
 *      action=downprocess&websignkey=<ajaxdata>&signs=<ajaxdata>&sign=<wp_sign>
 *      &websign=&kd=<kdns>&ves=1（有提取码时附加 p）
 *   5. 直链 = dom + "/file/" + url
 *
 * 风控：同 UA/IP 对同一分享页有访问频次限制，结果缓存 1 小时，
 * 批量解析每条间隔 1-3s 随机延时。
 */
@Singleton
class DirectLinkRepositoryImpl @Inject constructor(
    private val apiClient: LanzouApiClient,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore
) : DirectLinkRepository {

    private val okHttp get() = apiClient.okHttpClient

    override suspend fun resolve(shareUrl: String, password: String): Result<DirectLink> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 0) 缓存命中（TTL 1 小时）
                val cacheKey = if (password.isBlank()) shareUrl else "$shareUrl|pwd=$password"
                db.directLinkDao().getFresh(cacheKey, System.currentTimeMillis() - 3600_000L)?.let {
                    return@runCatching DirectLink(it.directUrl, it.fileName, it.referer)
                }

                // 1) 第三方解析服务（设置页可配置，可替换解析源）
                val thirdParty = settingsStore.thirdPartyResolverUrl.first()
                if (thirdParty.isNotBlank()) {
                    runCatching { resolveViaThirdParty(thirdParty, shareUrl, password) }
                        .getOrNull()?.let { return@runCatching it }
                    // 第三方失败则回落内置解析
                }

                val link = resolveInternal(shareUrl, password)
                db.directLinkDao().insert(
                    DirectLinkEntity(
                        shareUrl = cacheKey,
                        directUrl = link.url,
                        fileName = link.fileName,
                        referer = link.referer
                    )
                )
                link
            }
        }

    override suspend fun resolveBatch(urls: List<Pair<String, String>>): List<Result<DirectLink>> =
        withContext(Dispatchers.IO) {
            urls.mapIndexed { index, (url, pwd) ->
                if (index > 0) {
                    delay(ThreadLocalRandom.current().nextLong(
                        AppConstants.BATCH_DELAY_MIN_MS, AppConstants.BATCH_DELAY_MAX_MS + 1
                    ))
                }
                resolve(url, pwd)
            }
        }

    override suspend fun resolveFolder(shareUrl: String, password: String): Result<ResolveFolderResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 用原始链接所在域请求：t/k/fid/uid/puid 由该域服务端渲染页面时下发，
                // 跨域复用会被判非法（旧实现重写到 shareBase 后请求是失败诱因之一）。
                val origin = originBaseOf(shareUrl)
                val sharePage = getPage(shareUrl, origin)
                resolveFolderFromPage(sharePage, origin, password)
            }
        }

    /**
     * 已拿到分享页 HTML 时的文件夹解析（供 [resolveInternal] 自动分流复用，避免二次请求）。
     *
     * 实测样本 https://wwe.lanzoui.com/b01tpeg7i（2026-09）：
     *   页面 JS：$.ajax({ url:'/filemoreajax.php?file=2553948',
     *            data:{ 'lx':2,'fid':2553948,'uid':'1702063','puid':'ATBUN…',
     *                   'pg':pgs,'rep':'0','t':ib280v,'k':_h0k2o } })
     *   t/k 是随机变量名（ib280v / _h0k2o），值分别是 10 位时间戳与 32 位 hex。
     */
    private suspend fun resolveFolderFromPage(
        sharePage: String,
        origin: String,
        password: String
    ): ResolveFolderResult {
        val t = HtmlExtractor.extractT(sharePage)
            ?: throw ApiError.Business(-1, "无法提取 t（分享页结构可能已变化）")
        val k = HtmlExtractor.extractK(sharePage)
            ?: throw ApiError.Business(-1, "无法提取 k（分享页结构可能已变化）")
        val fid = HtmlExtractor.extractFid(sharePage)
            ?: throw ApiError.Business(-1, "无法提取文件夹 fid")
        // uid/puid 新版必带（实测 'uid':'1702063'、'puid':'ATBUN…'）；
        // 老页面没有时降级空串（服务端多数仍接受）
        val uid = HtmlExtractor.extractUid(sharePage).orEmpty()
        val puid = HtmlExtractor.extractPuid(sharePage).orEmpty()

        // 2) 翻页列出文件夹内全部文件
        val files = mutableListOf<Pair<String, String>>() // (fileUrl, pwd)
        var pg = 1
        while (true) {
            val resp = apiClient.apiService.getShareFileList(
                fileFid = fid,
                pg = pg,
                fid = fid,
                uid = uid,
                puid = puid,
                t = t,
                k = k,
                pwd = password
            )
            val batchEmpty = resp.items.isEmpty()
            resp.items.forEach { f ->
                val id = f.id
                if (!id.isNullOrBlank()) {
                    files.add("${origin.trimEnd('/')}/$id" to password)
                }
            }
            // 终止条件（2026-09 实测）：
            //   zt=1 还有下一页；zt=2 + text="no file" 取完；zt=3 提取码错误
            // 另加"本页 0 条"兜底，避免服务端异常时 pg>50 白跑 50 次。
            when (resp.zt) {
                1 -> if (batchEmpty) break else pg++
                2 -> break
                3 -> throw ApiError.Business(3, "提取码错误")
                else -> break
            }
            if (pg > 50) break
            delay(600)
        }
        if (files.isEmpty()) {
            throw ApiError.Business(-1, "文件夹为空或提取码错误")
        }

        // 3) 逐个解析直链（保留失败项计数，不静默丢弃）
        val results = resolveBatch(files)
        val links = results.mapNotNull { it.getOrNull() }
        return ResolveFolderResult(links, results.size - links.size, results.size)
    }

    // ==================== 内置解析 ====================

    /**
     * 内置直链解析（新版协议）。
     *
     * 自动分流：先取分享页，按页面特征判断是【单文件】还是【文件夹】：
     * - 文件夹页：含 filemoreajax.php 且没有下载 iframe → [resolveFolderFromPage]
     * - 单文件页：含 <iframe … src="/fn?…"> → iframe 里的 wp_sign 换直链
     * 这样用户粘贴 /bXXXX 目录链接也能直接解析，不必手动切换模式。
     */
    private suspend fun resolveInternal(shareUrl: String, password: String): DirectLink {
        val origin = originBaseOf(shareUrl)

        // 1) 分享页（可能被 acw_sc__v2 挑战拦截）
        var html = getPage(shareUrl, origin)
        html = solveAcwIfNeeded(html, shareUrl, origin)

        // 1.5) 失效页早退：实测返回 "文件不存在，或已删除" 的空壳页（约 1KB）
        if (html.contains("文件不存在") || html.contains("已取消分享")) {
            throw ApiError.Business(-1, "文件不存在或已删除")
        }

        // 1.6) 文件夹链接：单文件流程拿不到直链，抛出专用错误码，
        //     由调用方（ResolveViewModel）改走 resolveFolder 展开整个目录。
        //     这里绝不"悄悄返回目录里第一个文件"——那会静默丢弃其余文件。
        if (isFolderPage(html)) {
            throw ApiError.Business(ERR_FOLDER_LINK, "这是文件夹分享链接，请用「解析文件夹」展开全部文件")
        }

        // 2) 文件名：优先 <title>（实测 "Fluent v3.zip - 蓝奏云"），去掉站点后缀
        val fileName = extractTitle(html) ?: shareUrl.substringAfterLast('/')

        // 3) fid（var fid = 96810913;）
        val fid = HtmlExtractor.extractFileId(html)
            ?: throw ApiError.Business(-1, "无法提取文件 fid（页面结构可能已变化）")

        // 4) iframe 页（/fn?…）→ wp_sign / ajaxdata / kdns
        val iframeSrc = HtmlExtractor.extractIframe(html)
            ?: throw ApiError.Business(-1, "分享页未包含下载 iframe（文件可能已失效或需要提取码）")
        val iframeUrl = absolutize(iframeSrc, origin)
        var fnHtml = getPage(iframeUrl, shareUrl)
        fnHtml = solveAcwIfNeeded(fnHtml, iframeUrl, origin)

        val sign = HtmlExtractor.extractWpSign(fnHtml)
            ?: HtmlExtractor.extractSign(fnHtml)
            ?: throw ApiError.Business(-1, "无法提取签名 wp_sign（页面结构可能已变化）")
        val ajaxData = HtmlExtractor.extractAjaxData(fnHtml).orEmpty()
        val kd = HtmlExtractor.extractKdns(fnHtml) ?: 1

        // 5) POST ajaxfile.php?file=<fid>
        val form = FormBody.Builder()
            .add("action", "downprocess")
            .add("websignkey", ajaxData)
            .add("signs", ajaxData)
            .add("sign", sign)
            .add("websign", "")
            .add("kd", kd.toString())
            .add("ves", "1")
        if (password.isNotBlank()) form.add("p", password)

        // 直链接口与分享页同域：直接用原始域，token 与域绑定，跨域必失败
        val ajaxUrl = "${origin.trimEnd('/')}/ajaxfile.php?file=$fid"
        val body = okHttp.newCall(
            Request.Builder()
                .url(ajaxUrl)
                .header("Referer", iframeUrl)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .post(form.build())
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiError.Server(resp.code)
            resp.body?.string().orEmpty()
        }

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ApiError.Business(-1, "直链接口响应异常: ${body.take(120)}")
        val zt = json.optInt("zt", -1)
        if (zt != 1) {
            throw ApiError.Business(zt, json.optString("inf").ifBlank { "解析失败（zt=$zt）" })
        }

        val dom = json.optString("dom")
        val path = json.optString("url")
        if (dom.isBlank() || path.isBlank()) throw ApiError.Business(-1, "直链字段缺失（dom/url）")

        // 6) 直链 = dom + "/file/" + url
        //    注意：新版 url 以 '?' 开头，不能去掉前导字符
        val directUrl = "${dom.trimEnd('/')}/file/${path.removePrefix("/")}"
        val link = DirectLink(url = directUrl, fileName = fileName, referer = shareUrl)

        // 7) 探测是否为"验证中间页"（IP 风控时会先返回一个 HTML 验证页而非文件）。
        //    不探测的话用户会下载到一个 4KB 的 HTML —— 典型的"解析成功但下载不了"。
        return ensureDownloadable(link)
    }

    /**
     * 探测直链是否可直接下载；若返回的是验证中间页，则二次解析出真实下载地址。
     *
     * **2026-09 v1.3.4.9 对照实测：直链域名会下发两种截然不同的中间页，必须都处理。**
     *
     * ┌ 形态 A：业务验证页（老形态，domain=developer2.lanrar.com）
     * │   页面含 down_r(el) 与参数 file='…' / sign='…'
     * │   → POST 同目录 ajax.php {file, el, sign} → {"zt":1,"url":"真实下载地址"}
     * ├ 形态 B：acw 挑战页（新形态，domain=*.dmpdmp.com）
     * │   页面形如 <html><script>var arg1='40位HEX';(function(a,c){…})(a0i,0x760bf)…
     * │   ⚠️ 与分享页那种「纯 arg1 常量表」挑战不同：这里是一段**混淆 JS**，
     * │      必须在真实 JS 引擎里跑完才会 Set-Cookie acw_sc__v2。
     *       实测同一 session 连续请求 3 次仍返回挑战页 → 靠重试/换 UA 都过不去。
     * │   → 原版 v1.3.4.9 的做法（home_func.lua:11875 起）：
     * │     识别「var arg1=」后走 **WebView「带 header 重载」**，借系统 WebView
     * │     执行 JS 拿到 challenge cookie，再回主进程继续下载。
     * │   本实现同一思路：把挑战页丢给 [DirectLinkWebViewBridge]（WebView 执行），
     * │   拿回 acw_sc__v2 写进 CookieJar，然后重试**同一条直链**。
     * └ 形态 C：都不是 → 原样返回（真文件流）
     *
     * 只读前 1KB 判断，不下载正文；任何异常都回落原链（不阻塞解析）。
     */
    private fun ensureDownloadable(link: DirectLink): DirectLink = runCatching {
        val probe = okHttp.newCall(
            Request.Builder()
                .url(link.url)
                .header("Range", "bytes=0-1023")
                .header("Referer", link.referer)
                .build()
        ).execute()
        probe.use { resp ->
            if (!resp.isSuccessful) return@runCatching link
            val ct = resp.header("Content-Type").orEmpty()
            if (!ct.contains("text/html", ignoreCase = true)) return@runCatching link
            // 只读前 1KB：避免 Range 不被支持时把整个文件读进内存。
            // 用 byteStream 而非 okio —— okio 只是 okhttp 的传递依赖，编译期不可见。
            val input = resp.body?.byteStream() ?: return@runCatching link
            val buf = ByteArray(1024)
            val n = input.read(buf, 0, buf.size)
            if (n <= 0) return@runCatching link
            val head = String(buf, 0, n, Charsets.UTF_8)

            // ⚠️ 每个分支都必须 return@use（显式类型），否则 Kotlin 会把整块推断成
            //    Nothing，导致 use{} 的返回值类型与函数声明的 DirectLink 不符而编译失败。
            // 形态 A：业务验证页
            if (head.contains("down_r(")) {
                val real = resolveVerifiedUrl(link.url, head)
                return@use if (real != null) link.copy(url = real) else link
            }

            // 形态 B：acw 挑战页 —— 必须真跑 JS 才能拿到 cookie
            if (head.contains("var arg1=") || head.contains("acw_sc__v2")) {
                val cookie = challengeCookieFor(link)
                if (cookie != null) {
                    putChallengeCookie(link.url, cookie)
                    return@use link
                }
            }
            return@use link
        }
    }.getOrDefault(link)

    /**
     * 让 WebView 执行挑战页 JS，取回 acw_sc__v2 的完整 `name=value` 串。
     *
     * 为什么必须借 WebView：挑战 JS 是自解密的混淆代码（a0i/a0j 字符串表 +
     * 控制流平坦化），静态还原成本极高且作者随时可换；而系统 WebView 本身就是
     * 合法 JS 引擎，直接跑一遍最稳。原版 v1.3.4.9 也是这么做的。
     *
     * 失败返回 null（不抛），调用方回落到原链 —— 宁可让用户看到一个 HTML 提示页，
     * 也不要整个解析流程崩掉。
     */
    private fun challengeCookieFor(link: DirectLink): String? = runCatching {
        DirectLinkWebViewBridge.acquireCookieSync(link.url, link.referer)
    }.getOrNull()

    /** 把挑战 cookie 写进共享 CookieJar（后续下载走同一个 OkHttp 实例即自动携带） */
    private fun putChallengeCookie(url: String, cookie: String) {
        val name = cookie.substringBefore('=')
        val value = cookie.substringAfter('=', "")
        if (name.isBlank() || value.isBlank()) return
        val host = url.toHttpUrlOrNull()?.host ?: return
        runCatching {
            apiClient.cookieJar.putCookie(
                okhttp3.Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(host)
                    .path("/")
                    .build()
            )
        }
    }

    /** 验证中间页 → POST ajax.php 换真实下载地址 */
    private fun resolveVerifiedUrl(pageUrl: String, html: String): String? {
        val file = Regex("""['"]file['"]\s*:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: return null
        val sign = Regex("""['"]sign['"]\s*:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: return null
        val ajaxUrl = pageUrl.substringBeforeLast("/") + "/ajax.php"
        val form = FormBody.Builder()
            .add("file", file)
            .add("el", "1")
            .add("sign", sign)
            .build()
        val body = okHttp.newCall(
            Request.Builder()
                .url(ajaxUrl)
                .header("Referer", pageUrl)
                .post(form)
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        return runCatching { JSONObject(body) }
            .getOrNull()
            ?.optString("url")
            ?.takeIf { it.startsWith("http") }
    }

    // ==================== helpers ====================

    /**
     * 取分享链接自身的 scheme://host 作为请求基址。
     *
     * 为什么不用全局 shareBase：t / k / wp_sign / fid 都是**由该域名的服务端在渲染
     * 页面时动态下发**的，换域名复用会被判非法（旧实现把链接重写到 shareBase 后
     * 再请求，是解析失败的次要诱因）。原始域不可信（防钓鱼）时才回落 shareBase。
     */
    private fun originBaseOf(shareUrl: String): String {
        val url = shareUrl.toHttpUrlOrNull()
        val host = url?.host
        if (url != null && host != null && DomainUtils.isTrustedShareHost(host)) {
            return "${url.scheme}://$host"
        }
        return apiClient.domainInterceptor.snapshot().shareBase
    }

    /**
     * 页面是否为【文件夹分享页】。
     *
     * 判据（2026-09 实测两种页面对比）：
     * - 文件夹页：含 `filemoreajax.php`，且**没有** /fn? 下载 iframe（样本 wwe.lanzoui.com/b01tpeg7i）
     * - 单文件页：含 `<iframe … src="/fn?…">`，且不含 filemoreajax（样本 www.lanzoui.com/i1evj0klyr0d）
     */
    private fun isFolderPage(html: String): Boolean =
        html.contains("filemoreajax") && HtmlExtractor.extractIframe(html) == null

    /** 相对地址转绝对地址 */
    private fun absolutize(src: String, base: String): String =
        if (src.startsWith("http")) src
        else base.trimEnd('/') + if (src.startsWith("/")) src else "/$src"

    /**
     * 若页面是 acw_sc__v2 挑战（含 var arg1='…'），计算挑战值写入 CookieJar 后重新请求。
     * 写入走 CookieJar 而不是手动 Cookie 头：OkHttp BridgeInterceptor 会整体替换手动头。
     */
    private fun solveAcwIfNeeded(html: String, url: String, base: String): String {
        if (HtmlExtractor.extractAcwArg1(html) == null && !html.contains("acw_sc__v2")) return html
        val value = AcwScV2.compute(html)?.substringAfter('=') ?: return html
        val host = url.toHttpUrlOrNull()?.host
            ?: base.toHttpUrlOrNull()?.host
            ?: return html
        val cookie = okhttp3.Cookie.Builder()
            .name("acw_sc__v2")
            .value(value)
            .domain(host.removePrefix("www."))
            .path("/")
            .build()
        apiClient.cookieJar.putCookie(cookie)
        return getPage(url, base)
    }

    /** GET 页面（桌面 UA 由拦截器注入；非 2xx 直接抛错，避免错误页进正则误导排障） */
    private fun getPage(url: String, referer: String): String {
        val resp = okHttp.newCall(
            Request.Builder()
                .url(url)
                .header("Referer", referer)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
        ).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw ApiError.Server(resp.code)
        }
        return resp.body?.string().orEmpty()
    }

    /** 从 <title> 提取文件名：实测 "Fluent v3.zip - 蓝奏云" → "Fluent v3.zip" */
    private fun extractTitle(html: String): String? =
        Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?.trim()
            ?.let { raw ->
                raw.replace(Regex("""\s*-\s*蓝奏云\s*$"""), "").trim().ifBlank { null }
            }

    /** 第三方解析服务：POST shareUrl（+可选密码），期望返回 {"url": 直链} 或纯文本 URL */
    private fun resolveViaThirdParty(serviceUrl: String, shareUrl: String, password: String): DirectLink {
        val form = FormBody.Builder().add("url", shareUrl)
        if (password.isNotBlank()) form.add("pwd", password)
        val resp = okHttp.newCall(
            Request.Builder().url(serviceUrl).post(form.build()).build()
        ).execute()
        val body = resp.body?.string().orEmpty()
        val direct = runCatching { JSONObject(body).optString("url") }.getOrDefault("")
            .ifBlank { Regex("""https?://\S+""").find(body)?.value ?: "" }
        if (direct.isBlank()) throw ApiError.Business(-1, "第三方解析服务返回为空")
        return DirectLink(direct, shareUrl.substringAfterLast('/'), shareUrl)
    }
}
