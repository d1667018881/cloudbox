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

    override suspend fun resolve(shareUrl: String, password: String, force: Boolean): Result<DirectLink> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 0) 缓存命中（TTL 1 小时）。force=true 跳过：下载场景直链时效仅约
                //    30 分钟且挑战 cookie 逐次轮换，缓存的直链可能已死——宁可重解析
                val cacheKey = if (password.isBlank()) shareUrl else "$shareUrl|pwd=$password"
                if (!force) {
                    db.directLinkDao().getFresh(cacheKey, System.currentTimeMillis() - 3600_000L)?.let {
                        return@runCatching DirectLink(it.directUrl, it.fileName, it.referer)
                    }
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
                // V45：分享页获取统一走 fetchSharePage（含域名救护），origin 用活下来的域。
                val (effectiveUrl, sharePage) = fetchSharePage(shareUrl)
                val origin = originBaseOf(effectiveUrl)
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
        // V48（2026-10-05）：lx 实时提取（个人主页 /u 形态=1，文件夹 /b 形态=2）
        val lx = HtmlExtractor.extractLx(sharePage) ?: 2

        // 2) 翻页列出文件夹内全部文件
        val files = mutableListOf<Pair<String, String>>() // (fileUrl, pwd)
        var pg = 1
        while (true) {
            val resp = apiClient.apiService.getShareFileList(
                fileFid = fid,
                // V48：lx 从页面 JS 提取（/b 文件夹=2、/u 个人主页=1，
                // 硬编码 2 会让个人主页形态 zt=4 拒绝）
                lx = lx,
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
        // 0) 分享页 + 域名救护（V45：蓝奏子域会轮换性死亡，DNS 失败时换活域重试，
        //    成功后全程用活下来的 effectiveUrl——origin/referer 都跟实际域走）
        val (effectiveUrl, fetchedHtml) = fetchSharePage(shareUrl)
        val origin = originBaseOf(effectiveUrl)

        // 1) 分享页（可能被 acw_sc__v2 挑战拦截）
        var html = fetchedHtml
        html = solveAcwIfNeeded(html, effectiveUrl, origin)

        // 1.5) 失效页早退：实测返回 "文件不存在，或已删除" 的空壳页（约 1KB）
        if (html.contains("文件不存在") || html.contains("已取消分享")) {
            throw ApiError.Business(-1, "文件不存在或已删除")
        }

        // 1.55) 蓝奏政策墙（V45）：免费账号分享的 APK 禁止下载。页面原文
        //      「非会员不在支持分享apk文件」（"不在"是服务端原文错别字，勿改）。
        //      注意：此墙实测只拦移动端 UA，本 App 全程桌面 UA 碰不到——但用户
        //      可自定义 UA，识别它是为了万一命中时报真实原因，而不是误导性的
        //      「无法提取 fid」。此墙无下载元素，救护回退也救不了，直接报错。
        if (html.contains("非会员不在支持分享")) {
            throw ApiError.Business(
                -1,
                "蓝奏云政策：免费账号分享的 APK 已停止提供下载（需分享者开通会员）"
            )
        }

        // 1.6) 文件夹链接：单文件流程拿不到直链，抛出专用错误码，
        //     由调用方（ResolveViewModel）改走 resolveFolder 展开整个目录。
        //     这里绝不"悄悄返回目录里第一个文件"——那会静默丢弃其余文件。
        if (isFolderPage(html)) {
            throw ApiError.Business(ERR_FOLDER_LINK, "这是文件夹分享链接，请用「解析文件夹」展开全部文件")
        }

        // 2) 文件名：优先 <title>（实测 "Fluent v3.zip - 蓝奏云"），去掉站点后缀
        val fileName = extractTitle(html) ?: effectiveUrl.substringAfterLast('/')

        // 3) fid（var fid = 96810913;）
        val fid = HtmlExtractor.extractFileId(html)
            ?: throw ApiError.Business(-1, "无法提取文件 fid（页面结构可能已变化）")

        // 3.5) 新模板（2026-10-01 实测，无 iframe 形态）：分享页 JS 直接下发
        //      /tp/<id>?webtp=…，/tp 页 JS 变量拼直链（vkjxld + hyggid + lanosso）。
        //      老模板（iframe → apifile）分支保留，两代页面并存期间各自识别。
        HtmlExtractor.extractWebtpHref(html)?.let { webtpHref ->
            return resolveViaWebtp(webtpHref, origin, effectiveUrl, fileName)
        }

        // 4) iframe 页（/fn?…）→ wp_sign / ajaxdata / kdns
        val iframeSrc = HtmlExtractor.extractIframe(html)
            ?: throw ApiError.Business(-1, "分享页未包含下载 iframe（文件可能已失效或需要提取码）")
        val iframeUrl = absolutize(iframeSrc, origin)
        var fnHtml = getPage(iframeUrl, effectiveUrl)
        fnHtml = solveAcwIfNeeded(fnHtml, iframeUrl, origin)

        val sign = HtmlExtractor.extractWpSign(fnHtml)
            ?: HtmlExtractor.extractSign(fnHtml)
            ?: throw ApiError.Business(-1, "无法提取签名 wp_sign（页面结构可能已变化）")
        val ajaxData = HtmlExtractor.extractAjaxData(fnHtml).orEmpty()
        val kd = HtmlExtractor.extractKdns(fnHtml) ?: 1

        // 5) POST ajaxfile.php?file=<fid>
        //
        // 为什么不用 Retrofit 的 @POST 方法而在这里手工拼（V32 说明）：
        // 本请求必须带 Referer=iframeUrl 且域要与 token 同源，Retrofit 的
        // 静态接口声明表达不了"每请求动态 Referer"，所以走 OkHttp 直发。
        // （原先 LanzouApiService.downProcess 是一份等价的 Retrofit 声明，
        //   但从未被调用 —— 属于原型期残留，已删除。）
        //
        // 端点演进（勿回退到 ajaxm.php）：
        // 旧端点 ajaxm.php + {action, sign, file_id, p, kd, ves} 已随改版废弃。
        // 新版单文件页把签名藏在 iframe（/fn?…）的 `var wp_sign` 里，fid 用 URL
        // 查询传递，并新增 websignkey / signs / websign 三个校验字段。
        // 实测请求：
        //   POST /ajaxfile.php?file=96810913
        //   action=downprocess&websignkey=asXy&signs=asXy&sign=<wp_sign>&websign=&kd=1&ves=1
        // 成功响应：{"zt":1,"dom":"https://developer2.lanrar.com","url":"?A2VUags6…","inf":0}
        val form = FormBody.Builder()
            .add("action", "downprocess")
            .add("websignkey", ajaxData)
            .add("signs", ajaxData)
            .add("sign", sign)
            .add("websign", "")
            .add("kd", kd.toString())
            .add("ves", "1")
        if (password.isNotBlank()) form.add("p", password)

        // V44（2026-10-01 同步加固）：直链接口地址 —— 并列收集 iframe 页 JS 下发的
        // **全部候选**逐个试，拿到合法 JSON 的第一个即用。
        //
        // ⚠️ 候选为什么有多个：同一直链接口地址在页面上以两种形态轮换下发——
        //   老形态  url : 'https://apifile.woozooo.com/ajaxfile.php?file=…'
        //   新形态  var domain1='…' / var domain2='…'（2026-10-01 实测出现）
        // 加上无任何下发时的同域构造回落（V7 形态），共三层候选：
        //   407/HTML 的候选跳下一个，直到某个返回 JSON（含 zt）为止。
        //   ⚠️ 裸域名候选按老接口路径 /ajaxfile.php?file=<fid> 构造。
        val ajaxCandidates = buildList {
            HtmlExtractor.extractAjaxUrlCandidates(fnHtml).forEach { raw ->
                when {
                    raw.startsWith("http") -> add(raw)
                    raw.startsWith("//") -> add("https:$raw")
                    raw.startsWith("/") -> add("${origin.trimEnd('/')}$raw")
                    // 裸域名（domain1/domain2 形态）
                    else -> add("https://${raw.trimEnd('/')}/ajaxfile.php?file=$fid")
                }
            }
            // 老页面回落：页面无任何下发时同域构造（V7 逆向时的形态）
            add("${origin.trimEnd('/')}/ajaxfile.php?file=$fid")
        }.distinct()
        var body: String? = null
        for (cand in ajaxCandidates) {
            val b = runCatching {
                okHttp.newCall(
                    Request.Builder()
                        .url(cand)
                        .header("Referer", iframeUrl)
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .post(form.build())
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string().orEmpty()
                }
            }.getOrNull() ?: continue
            // 接口有效性以「响应是 JSON」为准（407 空体 / HTML 错误页都跳过）
            if (runCatching { JSONObject(b) }.isSuccess) {
                body = b
                break
            }
        }
        val bodySafe = body
            ?: throw ApiError.Business(-1, "直链接口全部候选不可用（页面结构可能已变化）")

        val json = JSONObject(bodySafe)
        val zt = json.optInt("zt", -1)
        if (zt != 1) {
            throw ApiError.Business(zt, json.optString("inf").ifBlank { "解析失败（zt=$zt）" })
        }

        val dom = json.optString("dom")
        val path = json.optString("url")
        if (dom.isBlank() || path.isBlank()) throw ApiError.Business(-1, "直链字段缺失（dom/url）")

        // 6) 直链 = dom + "/file/" + url + "&toolsdown"
        //    注意：新版 url 以 '?' 开头，不能去掉前导字符
        //    V44（2026-10-01 同步加固）：dom 子域已观察多轮换（slssctm → slsstm2 →
        //    developer4.lanrar.com…），必须始终用**返回的 dom**动态拼；且新形态
        //    dom 可能不带 scheme（裸域名），统一补 https://（OkHttp 遇无 scheme URL
        //    直接抛 IllegalArgumentException）。
        //    V47（2026-10-04）：真浏览器实测直链必带 &toolsdown 尾参（fn 页
        //    var down_3 下发，2026-10-04 实测固定 '&toolsdown'）——缺它服务端
        //    判"文件未授权"（rel=-1 JSON）。从 fn 页提取，提不到用固定值兜底。
        val domNorm = if (dom.startsWith("http")) dom else "https://${dom.trimEnd('/')}"
        val toolsdown = HtmlExtractor.extractDown3(fnHtml) ?: "&toolsdown"
        val directUrl = "${domNorm.trimEnd('/')}/file/${path.removePrefix("/")}$toolsdown"
        val link = DirectLink(url = directUrl, fileName = fileName, referer = effectiveUrl)

        // 7) 探测是否为"验证中间页"（IP 风控时会先返回一个 HTML 验证页而非文件）。
        //    不探测的话用户会下载到一个 4KB 的 HTML —— 典型的"解析成功但下载不了"。
        return ensureDownloadable(link)
    }

    /**
     * 新模板（2026-10-01，无 iframe 形态）解析。
     *
     * 流程：分享页 JS 下发 '/tp/<id>?webtp=…' → GET 该页 → 直链由 JS 变量拼出：
     * `vkjxld + hyggid + lanosso`（lanosso 实测为空串，保留拼接以防服务端启用）。
     *
     * ⚠️ 直链域（developer4.lanrar.com）首次 GET 返回的是 **gzip 包裹的 acw
     * 挑战页**（V44 同步加固时实测推翻了此前"返回真文件流"的误判）——由
     * [ensureDownloadable] 识别并拆挑战后才是真文件，见其注释形态 G。
     *
     * 已知边界：带提取码的分享在新模板下的提交方式尚未实测（手上样本无密码），
     * 缺 vkjxld/hyggid 时抛带特征的业务错误，便于真机反馈定位。
     */
    private suspend fun resolveViaWebtp(
        webtpHref: String,
        origin: String,
        shareUrl: String,
        fileName: String
    ): DirectLink {
        val tpUrl = absolutize(webtpHref, origin)
        var tpHtml = getPage(tpUrl, shareUrl)
        tpHtml = solveAcwIfNeeded(tpHtml, tpUrl, origin)
        val base = HtmlExtractor.extractJsVar(tpHtml, "vkjxld")
            ?: throw ApiError.Business(-1, "新模板 /tp 页缺 vkjxld（页面结构可能又变化，或需要提取码）")
        val h = HtmlExtractor.extractJsVar(tpHtml, "hyggid")
            ?: throw ApiError.Business(-1, "新模板 /tp 页缺 hyggid（页面结构可能又变化）")
        val lanosso = HtmlExtractor.extractJsVar(tpHtml, "lanosso") ?: ""
        val directUrl = base + h + lanosso
        // referer 用分享页地址：直链域若校验来源，同站分享页最保险
        return ensureDownloadable(DirectLink(url = directUrl, fileName = fileName, referer = shareUrl))
    }

    /**
     * 探测直链是否可直接下载；不能则逐级拆中间页。
     *
     * **2026-10-01 V44 同步加固：直链域一次解析里可能按序出现四种形态。**
     *
     * ┌ 形态 G：gzip 包裹的 acw 挑战页（新！⚠️ 不带 Content-Encoding 头，
     * │   OkHttp 不会透明解压，裸字节 1f 8b 开头——旧探测按 Content-Type/明文
     * │   匹配全部漏判，用户会把 2.7KB 的 gzip 挑战页当文件存下来）
     * │   → gunzip → 静态 arg1 挑战 → [AcwScV2] 算 cookie 写共享 CookieJar
     * │     → 带 cookie 重试**同一条直链**（2026-10-01 实测：干净 IP 上即为
     * │       真文件；被风控的 IP 会落到形态 A）
     * ├ 形态 B：明文 acw 挑战页（var arg1='40位HEX'）
     * │   → 同上先静态算（主站同款算法，直链域挑战同为纯 arg1 常量表）；
     * │     静态算不动（混淆 JS 挑战）再走 [DirectLinkWebViewBridge] 兜底
     * ├ 形态 A：业务验证页（down_r + file/sign → POST 同目录 ajax.php）
     * │   → zt=1 的 url 即真实下载地址（IP 风控时出现）
     * └ 形态 C：真文件流 → 原样返回
     *
     * 只读前 64KB 判断（gzip 挑战页需要完整流才能解；探测连接是一次性的，
     * 真实下载是后续独立请求，读到 64KB 即断开无副作用）；任何异常回落原链。
     */
    /**
     * 逐级拆直链中间页，**出口严格化**（V48，2026-10-05 重构）。
     *
     * 形态链（实测 2026-10-05）：gzip 挑战 → 验证页 → 真 CDN 直链。
     * 每轮 [probeMiddlePage] 返回 (head, finalUrl)：
     * - head == null → 已是文件流，finalUrl 即 302 跟随后的 CDN 地址，直接采用
     *  （V47 的 finalizeUrl 并入此处：探测请求本身就带最终 URL，省一次重复
     *   请求，并消除"探测时是文件、finalize 时变挑战"的时序缝隙）
     * - head 是验证页 → [resolveVerifiedUrl]；全 el 失败 → **抛错**（V47 及以前
     *   回落原始直链交给 DownloadManager，无 JS 必吃挑战页 → 假文件 →
     *   TA 真机"点下载=未知/失败"的直接成因。宁可明确报错，不假成功）
     * - head 是挑战 → 算 cookie 进 jar（trio cookie 已由 saveFromResponse
     *   按名字放行，不再被域白名单丢弃）→ 下一轮
     *
     * 循环上限 4（挑战+验证+1 轮裕量）；用尽仍非文件流 → 抛错。
     * 仅网络级异常（IOException）回落原链（保底可用浏览器打开的场景）。
     */
    private fun ensureDownloadable(link: DirectLink): DirectLink {
        var current = link
        repeat(4) {
            val probe = try {
                probeMiddlePage(current)
            } catch (e: java.io.IOException) {
                return current // 网络故障：原链交回（DownloadManager 可能自行重试）
            }
            if (probe.head == null) {
                // 文件流：URL 换成跟随 302 后的最终地址（referer 同步换直链，
                // 贴真浏览器 302 后的请求形态；CDN 实测裸请求可下）
                return if (probe.finalUrl != current.url) {
                    current.copy(url = probe.finalUrl, referer = current.url)
                } else current
            }
            when {
                // 形态 A：业务验证页 → POST 同目录 ajax.php 拿真实下载地址。
                // V48（2026-10-05）：OkHttp 直连 POST 实测被服务端拒（校验
                // JS 环境/指纹，头无法复刻）→ 失败后 WebView 兜底自动过验证。
                probe.head.contains("down_r(") -> {
                    val real = resolveVerifiedUrl(current.url, probe.head)
                        ?: runCatching {
                            DirectLinkWebViewBridge.acquireVerifiedUrlSync(
                                current.url, current.referer
                            )
                        }.getOrNull()
                        ?: throw ApiError.Business(
                            -1,
                            "下载链接需要人机验证，自动通过失败——请稍后重试，或复制链接到浏览器下载"
                        )
                    current = current.copy(url = real)
                }
                // 形态 B/G：acw 挑战 —— 静态算法优先，WebView 兜底
                else -> {
                    val staticValue = AcwScV2.compute(probe.head)?.substringAfter('=')
                    if (staticValue != null) {
                        putAcwCookie(current.url, staticValue)
                    } else {
                        val wvCookie = challengeCookieFor(current)
                            ?: throw ApiError.Business(
                                -1,
                                "反爬挑战无法解开（算法可能已更新）——请稍后重试，或复制链接到浏览器下载"
                            )
                        putChallengeCookie(current.url, wvCookie)
                    }
                    // 带 cookie 下一轮重试同一直链
                }
            }
        }
        throw ApiError.Business(
            -1,
            "直链多重挑战未通过（服务端风控升级）——请稍后重试，或复制链接到浏览器下载"
        )
    }

    /** 探测结果：head=中间页可读文本（null=真文件流）；finalUrl=跟随 302 后的最终地址 */
    private data class ProbeResult(val head: String?, val finalUrl: String)

    /**
     * 探测直链响应：是中间页（gzip 挑战 / 明文挑战 / 验证页）返回其可读文本，
     * 是真文件流返回 head=null。
     *
     * gzip 魔数（1f 8b）判定优先于一切 Content-Type——挑战页 gzip 是**不带
     * Content-Encoding 的裸 gzip**，OkHttp 原样透传；解开后无挑战/验证标记的
     * gzip 是真 .gz 文件，同样按文件流处理（解不出完整流按 null 处理，不误杀）。
     *
     * 网络异常直接抛出（调用方区分"网络故障"与"文件流"——runCatching 吞掉
     * 异常返回 null 会让两者混淆，V48 起 IOException 上抛）。
     */
    private fun probeMiddlePage(link: DirectLink): ProbeResult {
        okHttp.newCall(
            Request.Builder()
                .url(link.url)
                .header("Referer", link.referer)
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return ProbeResult(null, resp.request.url.toString())
            val input = resp.body?.byteStream() ?: return ProbeResult(null, resp.request.url.toString())
            // 64KB 上限：挑战/验证页实测 < 5KB；真文件读到 64KB 即可判"非 HTML"
            val buf = ByteArray(64 * 1024)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
            if (n < 2) return ProbeResult(null, resp.request.url.toString())
            val body = if (buf[0] == 0x1f.toByte() && buf[1] == 0x8b.toByte()) {
                // gunzip；截断的 gzip（真 .gz 文件被 64KB 截断）解不出挑战标记 → 空串 → null
                runCatching {
                    java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(buf, 0, n))
                        .use { s -> String(s.readBytes(), Charsets.UTF_8) }
                }.getOrDefault("")
            } else {
                String(buf, 0, n, Charsets.UTF_8)
            }
            val head = when {
                body.contains("var arg1=") || body.contains("acw_sc__v2") -> body
                body.contains("down_r(") -> body
                else -> null
            }
            return ProbeResult(head, resp.request.url.toString())
        }
    }

    /** 把静态算出的 acw_sc__v2 写进共享 CookieJar（与 [solveAcwIfNeeded] 同域规则） */
    private fun putAcwCookie(url: String, value: String) {
        if (value.isBlank()) return
        val host = url.toHttpUrlOrNull()?.host ?: return
        runCatching {
            apiClient.cookieJar.putCookie(
                okhttp3.Cookie.Builder()
                    .name("acw_sc__v2")
                    .value(value)
                    .domain(host.removePrefix("www."))
                    .path("/")
                    .build()
            )
        }
    }

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
        // V44（2026-10-01）：验证页实测有 down_r(1)/down_r(2)/down_r(3) 三个按钮，
        // 单 el=1 实测返回 zt=0「验证码错误」——依次试 1..3，拿到 zt=1 的 url 即真链。
        for (el in 1..3) {
            val form = FormBody.Builder()
                .add("file", file)
                .add("el", el.toString())
                .add("sign", sign)
                .build()
            val body = okHttp.newCall(
                Request.Builder()
                    .url(ajaxUrl)
                    .header("Referer", pageUrl)
                    .post(form)
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
            val url = runCatching { JSONObject(body) }
                .getOrNull()
                ?.optString("url")
                ?.takeIf { it.startsWith("http") }
            if (url != null) return url
        }
        return null
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
     * 取分享页 HTML，DNS 失败时做**域名救护**（V45，2026-10-03）。
     *
     * 背景：蓝奏域名系轮换性死亡——实测 wwbig/www/wwt.lanzouq.com 子域 A 记录
     * 被服务端整体删除，用户侧表现为「昨天还能解析，今天报网络错误」。而分享
     * ID 是全局的：同一 ID 换任意活域同路径照样打开（2026-10-03 逐个实测，
     * 候选表见 [DomainUtils.fallbackShareHosts]）。
     *
     * 策略：原 URL 正常请求；仅当抛 [java.net.UnknownHostException]（DNS 解析
     * 失败）时才逐候选试活域，第一个 2xx 的胜出。**HTTP 层错误（404/403…）
     * 不回退**——那是链接或服务问题，换域救不了，原样上抛保留真实错误。
     *
     * @return (effectiveUrl, html)：effectiveUrl 是**实际拿到页面的 URL**，
     *         后续 origin/referer/直链 referer 必须全用它（跨域 referer 会被判非法）。
     */
    private fun fetchSharePage(shareUrl: String): Pair<String, String> {
        val origin = originBaseOf(shareUrl)
        try {
            return shareUrl to getPage(shareUrl, origin)
        } catch (e: Exception) {
            // V48d（2026-10-06）：救护触发从「DNS 死」扩到「域级故障」。
            // 实锤案例：lanzoux.com SSL 证书 2026-08-31 过期未续（AlphaSSL），
            // OkHttp 抛 SSLException("Chain validation failed")——域名活着但
            // 证书死了，换活域同分享 ID 照常打开（TA 实测 lanzouq 可用）。
            // SSL 族全认：过期/自签/链不完整/主机名不匹配，都是「这个域的
            // HTTPS 坏了」而不是「网络不可用」，换域都能救。
            if (!isDomainLevelFailure(e)) throw e
            val host = shareUrl.toHttpUrlOrNull()?.host ?: throw e
            val candidates = DomainUtils.fallbackShareHosts(host)
                .filter { it != host }
                .map { candidateHost ->
                    // 只换 scheme://host 段，路径（分享 ID）原样保留
                    shareUrl.replaceFirst(Regex("""^(https?://)[^/]+"""), "$1$candidateHost")
                }
            for (candidateUrl in candidates) {
                try {
                    val html = getPage(candidateUrl, originBaseOf(candidateUrl))
                    return candidateUrl to html
                } catch (e2: Exception) {
                    // 该候选域也坏 → 试下一个（同样只认域级故障，
                    // 网络/超时类异常直接抛，避免误导排障方向）
                    if (!isDomainLevelFailure(e2)) throw e2
                }
            }
            // 全部候选失败：抛原始异常（别吞——「网络不可用」和「域名死绝」的
            // 排障路径不同，保留原始信息给用户看真实原因）
            throw e
        }
    }

    /**
     * 异常是否为「域级故障」（换域可救）：DNS 解析失败或 TLS 证书问题。
     * 沿 cause 链找——OkHttp/Retrofit 会把底层异常包多层。
     */
    private fun isDomainLevelFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            when (t) {
                is java.net.UnknownHostException -> return true
                // SSLHandshakeException / SSLPeerUnverifiedException 都是 SSLException 子类；
                // 证书过期在 Conscrypt 上表现为 SSLException("Chain validation failed")
                is javax.net.ssl.SSLException -> return true
                is java.security.cert.CertificateException -> return true
            }
            t = t.cause
            depth++
        }
        return false
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
