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
import com.cloudbox.app.core.domain.repository.ResolveFolderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 直链解析仓库实现（核心模块，需求规格 7 节，严格按流程实现）。
 *
 * 解析流程：
 * 1. GET 分享页 https://{shareBase}/{shareId}，带桌面 UA 和 Referer
 * 2. 正则提取 sign：有提取码分支 'sign=(\w+?)&'，无提取码分支先取 iframe 再提取
 * 3. POST https://{shareBase}/ajaxm.php
 *    action=downprocess&sign=<sign>&file_id=<文件id>&p=<密码>&kd=1
 * 4. 响应 {"zt":1, "dom":"https://xxx.lanzoux.com", "url":"/xxxx.html"}
 * 5. 直链 = dom + '/file/' + url
 *    （与需求规格"拼接 dom+url"的差异：LanZouCloud-API 源码证实中间还有 /file/ 段，
 *    已按更近期源码修正并在此标注）
 *
 * 风控说明：同 UA/IP 对同一分享页 7 天内访问上限约 5 次，超限临时拉黑。
 * 因此解析结果缓存 1 小时（TTL），批量解析每条间隔 1-3s 随机延时。
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
                // 缓存（TTL 1h）
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
                    // 批量解析 1-3s 随机延时：防止同 UA/IP 高频访问被风控拉黑（需求规格 7 节）
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
                val base = apiClient.domainInterceptor.snapshot().shareBase
                // 1) GET 分享页拿 t/k/fid（filemoreajax 必需参数，每次实时提取禁止缓存）
                val sharePage = getPage(shareUrl, base)
                val t = HtmlExtractor.extractT(sharePage) ?: throw ApiError.Business(-1, "无法提取 t")
                val k = HtmlExtractor.extractK(sharePage) ?: throw ApiError.Business(-1, "无法提取 k")
                val fid = HtmlExtractor.extractFid(sharePage)?.toLongOrNull()
                    ?: throw ApiError.Business(-1, "无法提取文件夹 fid")

                // 2) 翻页列出文件夹内全部文件
                val files = mutableListOf<Pair<String, String>>() // (fileUrl, pwd)
                var pg = 1
                while (true) {
                    val resp = apiClient.apiService.getShareFileList(
                        lx = 2, pg = pg, k = k, t = t, fid = fid, pwd = password
                    )
                    resp.text?.forEach { f ->
                        files.add("${base.trimEnd('/')}/${f.id}" to password)
                    }
                    // zt=1 还有下一页，zt=2 取完，zt=3 提取码错误
                    when (resp.zt) {
                        1 -> pg++
                        2 -> break
                        3 -> throw ApiError.Business(3, "提取码错误")
                        else -> break
                    }
                    // 每页间隔（源码 sleep(0.6) 同款，防风控）
                    delay(600)
                }

                // 3) 逐个解析直链（#13 修复：保留失败项计数，不静默丢弃）
                val results = resolveBatch(files)
                val links = results.mapNotNull { it.getOrNull() }
                ResolveFolderResult(links, results.size - links.size, results.size)
            }
        }

    // ==================== 内置解析 ====================

    private suspend fun resolveInternal(shareUrl: String, password: String): DirectLink {
        val base = apiClient.domainInterceptor.snapshot().shareBase
        // 分享页可能已带旧域名（lanzoux 等），统一重写到当前 shareBase 以防 404
        val shareId = DomainUtils.extractShareId(shareUrl)
            ?: throw ApiError.Business(-1, "无法识别分享链接: $shareUrl")
        val sharePageUrl = "${base.trimEnd('/')}/$shareId"
        var html = getPage(sharePageUrl, base)

        // acw_sc__v2 反爬挑战：页面出现该 cookie 时需先计算再重新请求（社区逆向算法，见 AcwScV2）
        if (html.contains("acw_sc__v2")) {
            val cookieValue = AcwScV2.compute(html)
            if (cookieValue != null) {
                // #5 修复：把计算出的 acw_sc__v2 写入 CookieJar（按 lanzou 域），
                // 由 OkHttp BridgeInterceptor 统一拼接 Cookie 头。
                // 旧实现手动 header("Cookie", ...) 会被 BridgeInterceptor 在 cookieJar
                // 返回非空时整体替换（首次访问已 Set-Cookie acw_tc 等），导致重试必然失败
                val host = java.net.URI(base).host ?: "www.lanzou.com"
                val cookie = okhttp3.Cookie.Builder()
                    .name("acw_sc__v2")
                    .value(cookieValue.substringAfter('='))
                    .domain(host.removePrefix("www."))
                    .path("/")
                    .build()
                apiClient.cookieJar.putCookie(cookie)
                html = getPage(sharePageUrl, base)
            }
        }

        // 提取文件 id（ajaxm.php 的 file_id 参数；部分版本不需要，保留兼容）
        val fileId = HtmlExtractor.extractFileId(html)

        // 提取 sign（两种分支）：
        // 有提取码：sign=(\w+?)& 直接可提取
        var sign = HtmlExtractor.extractSign(html)
        if (sign == null && password.isBlank()) {
            // 无提取码：先取 iframe 页再提取（LanZouCloud-API 无提取码分支）
            val iframe = HtmlExtractor.extractIframe(html)
            if (iframe != null) {
                val iframeUrl = if (iframe.startsWith("http")) iframe else base.trimEnd('/') + iframe
                val iframeHtml = getPage(iframeUrl, base)
                sign = HtmlExtractor.extractSign(iframeHtml)
            }
        }
        sign ?: throw ApiError.Business(-1, "无法从页面提取 sign（页面结构可能已变化）")

        // POST ajaxm.php 拿直链
        val form = FormBody.Builder()
            .add("action", "downprocess")
            .add("sign", sign)
        fileId?.let { form.add("file_id", it) }
        if (password.isNotBlank()) form.add("p", password)
        form.add("kd", "1")

        val resp = okHttp.newCall(
            Request.Builder()
                .url("https://${AppConstants.PLACEHOLDER_HOST}/ajaxm.php")
                .header("Referer", sharePageUrl)
                .post(form.build())
                .build()
        ).execute()
        val body = resp.body?.string().orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ApiError.Business(resp.code, "ajaxm.php 响应异常: ${body.take(100)}")
        val zt = json.optInt("zt", -1)
        if (zt != 1) throw ApiError.Business(zt, json.optString("inf", "解析失败"))

        val dom = json.optString("dom")
        val path = json.optString("url")
        if (dom.isBlank() || path.isBlank()) throw ApiError.Business(-1, "直链字段缺失")

        // 直链 = dom + '/file/' + url（源码确认的拼接规则）
        val directUrl = "${dom.trimEnd('/')}/file/${path.removePrefix("/")}"
        return DirectLink(
            url = directUrl,
            fileName = json.optString("inf", shareId),
            referer = sharePageUrl
        )
    }

    /** GET 页面（带桌面 UA 与 Referer；#21 修复：非 2xx 抛业务错误，避免错误页进正则误导排障） */
    private fun getPage(url: String, base: String): String {
        val builder = Request.Builder()
            .url(url)
            .header("Referer", base)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        val resp = okHttp.newCall(builder.build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw ApiError.Server(resp.code)
        }
        return resp.body?.string().orEmpty()
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
