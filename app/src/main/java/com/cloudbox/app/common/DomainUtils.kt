package com.cloudbox.app.common

/**
 * 域名工具：URL 规范化 / 黑名单过滤 / 分享 ID 提取。
 */
object DomainUtils {

    /** 补全 scheme 与末尾斜杠，保证拼接行为一致 */
    fun normalize(base: String): String {
        var s = base.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        if (!s.endsWith("/")) s += "/"
        return s
    }

    /** 黑名单过滤：lanzous.com 被抢注，任何来源（含远程配置）都不得使用 */
    fun isForbidden(domain: String): Boolean {
        return try {
            val host = java.net.URI(normalize(domain)).host?.lowercase() ?: return false
            AppConstants.FORBIDDEN_DOMAINS.any { host == it || host.endsWith(".$it") }
        } catch (_: Exception) {
            false
        }
    }

    /** 从分享链接中提取分享 ID，失败返回 null */
    fun extractShareId(url: String): String? {
        // 兼容 lanzou.com/i5g8y1a 与 lanzou.com/i5g8y1a/ 两种形态
        val clean = url.trim().trimEnd('/')
        return AppConstants.SHARE_ID_REGEX.find(clean)?.groupValues?.get(1)
    }

    /** 判断是否为受支持的分享链接 */
    fun isShareUrl(url: String): Boolean = AppConstants.SHARE_URL_REGEX.containsMatchIn(url.trim())
}
