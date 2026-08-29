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

    /** 判断是否为受支持的分享链接：正则初筛 + host 后缀白名单双重校验，
     *  防止钓鱼域名（如 lanzoucloud.com）被误判。 */
    fun isShareUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!AppConstants.SHARE_URL_REGEX.containsMatchIn(trimmed)) return false
        val host = runCatching { java.net.URI(trimmed).host?.lowercase() }.getOrNull() ?: return false
        return isTrustedShareHost(host)
    }

    /** host 是否属于已知可信的分享/接口域名后缀 */
    fun isTrustedShareHost(host: String): Boolean {
        val h = host.lowercase()
        return AppConstants.TRUSTED_SHARE_HOSTS.any { h == it || h.endsWith(".$it") }
    }
}
