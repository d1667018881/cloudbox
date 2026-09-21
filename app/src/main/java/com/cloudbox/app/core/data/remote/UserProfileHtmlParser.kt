package com.cloudbox.app.core.data.remote

import com.cloudbox.app.core.domain.model.UserProfile

/**
 * 「账户概览」页（`myfile.php?item=1&v2`）HTML 解析器。
 *
 * 逐条照搬原版 `home_func.lua` 的 `获取用户信息`（:2035 起）里的 Lua 模式，
 * `(.-)`（懒惰匹配）对应这里的 `(.*?)`。页面里不少字段是「下载<div class="folsha3">」
 * 这种裸文本 + div 的结构，没有稳定的 id/class，正则比 Jsoup 选择器更贴原版、更抗改动。
 *
 * ⚠️ 未登录/过期的响应是登录页 HTML，所有字段都会落空 ——
 * 调用方用 [UserProfile.valid] 判定，别把"未登录"当成"账号信息为空"。
 */
object UserProfileHtmlParser {

    private val RE_USER_NAME =
        Regex("<div class=\"c_topr\">(.*?)<span", RegexOption.DOT_MATCHES_ALL)
    private val RE_DISPLAY_NAME =
        Regex("<div class=\"c_top1\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)

    // 个人分享链：原版依次尝试三种页面形态，命中即止
    private val RE_SHARE_1 = Regex("\\);\">(.*?)<br>", RegexOption.DOT_MATCHES_ALL)
    private val RE_SHARE_2 = Regex("\\);\">(.*?)</div><", RegexOption.DOT_MATCHES_ALL)
    private val RE_SHARE_QR = Regex("QRCode\\((.*?)QRCode\\.", RegexOption.DOT_MATCHES_ALL)

    private val RE_SHARE_CODE =
        Regex("<span class=\"shapwd\">密码:(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
    private val RE_UBT = Regex("id=\"ubt\" name=\"ubt\" value=\"([^\"]*)\"")
    private val RE_USM = Regex("id=\"usm\" name=\"usm\" value=\"([^\"]*)\"")
    private val RE_PUBLISHER = Regex("id=\"shownames\" name=\"shownames\" value=\"([^\"]*)\"")
    private val RE_DOWNLOAD =
        Regex("下载<div class=\"folsha3\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
    private val RE_FILE_COUNT =
        Regex("文件数<div class=\"folsha3\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)

    /** 页面出现该片段 = "不显示发布者"（原版据此反推 show 开关，见 home_func.lua:2319-2325） */
    private const val MARK_HIDE_PUBLISHER = "#pwd2s{display:none}"

    fun parse(html: String): UserProfile {
        if (html.isBlank()) return UserProfile()
        val shareLink = RE_SHARE_1.find(html)?.groupValues?.get(1)
            ?: RE_SHARE_2.find(html)?.groupValues?.get(1)
            ?: RE_SHARE_QR.find(html)?.groupValues?.get(1)
        return UserProfile(
            userName = RE_USER_NAME.find(html)?.groupValues?.get(1).clean(),
            displayName = RE_DISPLAY_NAME.find(html)?.groupValues?.get(1).clean(),
            shareLink = shareLink.clean(),
            shareLinkCode = RE_SHARE_CODE.find(html)?.groupValues?.get(1).clean(),
            externalLinkTitle = RE_UBT.find(html)?.groupValues?.get(1).clean(),
            externalLinkSummary = RE_USM.find(html)?.groupValues?.get(1).clean(),
            publisherVisible = !html.contains(MARK_HIDE_PUBLISHER),
            publisherName = RE_PUBLISHER.find(html)?.groupValues?.get(1).clean(),
            downloadCount = RE_DOWNLOAD.find(html)?.groupValues?.get(1).clean(),
            fileCount = RE_FILE_COUNT.find(html)?.groupValues?.get(1).clean()
        )
    }

    /** 去空白；空串归 null（正则落空时给的是 "" 或 null） */
    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
