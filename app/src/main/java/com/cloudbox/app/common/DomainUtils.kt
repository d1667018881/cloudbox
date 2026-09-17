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

    /**
     * 判断是否为受支持的分享链接：正则初筛 + host 后缀白名单双重校验，
     *  防止钓鱼域名（如 lanzoucloud.com）被误判。
     *
     * ⚠️ 传入的必须是**干净的 URL**，不能是整段分享文本。
     * 整段文本请先过 [extractShareUrl] —— 原因见那里的注释。
     */
    fun isShareUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!AppConstants.SHARE_URL_REGEX.containsMatchIn(trimmed)) return false
        // 从字符串里挖 host 时必须用宽松提取，不能用 java.net.URI：
        // 中文、空格、尾部标点都会让 URI 抛 URISyntaxException（详见 extractShareUrl）。
        val host = hostOf(trimmed) ?: return false
        return isTrustedShareHost(host)
    }

    /**
     * 从**任意文本**里提取第一个蓝奏云分享链接。
     *
     * ─────────────────────────────────────────────────────────────
     * 为什么必须有这个方法（2026-09-15 修 "粘贴链接解析不出"）
     * ─────────────────────────────────────────────────────────────
     * 旧实现拿**整段文本**去调 `java.net.URI(text)`，只要文本里出现
     * 中文、空格或尾部标点就抛 `URISyntaxException`，被 `?: return false`
     * 吞掉 → 整条被判为"不是分享链接" → 解析页报「未识别到蓝奏云分享链接」。
     *
     * 实测（Java 20，java.net.URI 行为）：
     * ```
     * "https://wwt.lanzouj.com/iXXXXXXX"                → host 正常
     * "蓝奏云盘 https://wwt.lanzouj.com/iXXXXXXX"        → URISyntaxException
     * "https://wwt.lanzouj.com/iXXXXXXX 提取码：abcd"    → URISyntaxException
     * "https://wwt.lanzouj.com/iXXXXXXX "（仅尾部空格）  → URISyntaxException
     * ```
     * 而微信/QQ/抖音分享出来的标准形态恰好就是第二种，**尾部几乎总带空格或换行**。
     * 这就是"口令/纯链接能解析、直接粘贴整段分享文本不行"的根因。
     *
     * 正确做法：先用正则把 URL 子串**抠出来**，再做域名校验。
     *
     * @return 干净的 URL（已去尾部标点），不是分享链接则返回 null
     */
    fun extractShareUrl(text: String): String? {
        if (text.isBlank()) return null
        // 逐个候选 URL 试：一段文本里可能有多条链接，取第一条可信的
        for (m in AppConstants.URL_CANDIDATE_REGEX.findAll(text)) {
            val candidate = m.value.trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '。', '，', '、', '（', '(', '）')
            if (candidate.isEmpty()) continue
            if (!AppConstants.SHARE_URL_REGEX.containsMatchIn(candidate)) continue
            val host = hostOf(candidate) ?: continue
            if (isTrustedShareHost(host)) return candidate
        }
        return null
    }

    /**
     * 从文本里提取**全部**可信分享链接（去重，保持出现顺序）。
     * 备注里可能贴了好几条链接，批量解析要用这个。
     */
    fun extractShareUrls(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in AppConstants.URL_CANDIDATE_REGEX.findAll(text)) {
            val candidate = m.value.trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '。', '，', '、', '（', '(', '）')
            if (candidate.isEmpty()) continue
            if (!AppConstants.SHARE_URL_REGEX.containsMatchIn(candidate)) continue
            val host = hostOf(candidate) ?: continue
            if (isTrustedShareHost(host)) out.add(candidate)
        }
        return out.toList()
    }

    /**
     * 宽松取 host：不依赖 `java.net.URI`（它对非 ASCII 会抛异常）。
     *
     * 手工解析 `scheme://[user@]host[:port]/path` 里的 host 段，
     * 只取到最后一个 `@` 之后、第一个 `/` `?` `#` `:` 之前的部分。
     */
    private fun hostOf(url: String): String? {
        val s = url.trim()
        val schemeEnd = s.indexOf("://")
        if (schemeEnd < 0) return null
        var rest = s.substring(schemeEnd + 3)
        // 去掉 userinfo（user:pass@host）
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)
        // 截到 path/query/fragment/port 之前
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == ':' }
        val host = if (end < 0) rest else rest.substring(0, end)
        return host.lowercase().ifEmpty { null }
    }

    /**
     * 从分享文本里提取访问码 / 提取码（"提取码：abcd" / "密码: abcd" / "（访问码：abcd）"）。
     * 拿到之后可以自动填入解析页的密码框，省得用户手动敲。
     */
    fun extractPassword(text: String): String? {
        val m = AppConstants.SHARE_PWD_REGEX.find(text) ?: return null
        val pwd = m.groupValues[1].trim()
        return pwd.ifEmpty { null }
    }

    /** host 是否属于已知可信的分享/接口域名。
     *  用正则匹配 lanzou+可选单字母变体（lanzouw.com 等全部变体，蓝奏云换域名无需改代码），
     *  钓鱼域（lanzoucloud.com / evil-lanzou.com）结构上无法匹配，lanzous.com 由黑名单拦截。 */
    fun isTrustedShareHost(host: String): Boolean {
        val h = host.lowercase()
        if (AppConstants.FORBIDDEN_DOMAINS.any { h == it || h.endsWith(".$it") }) return false
        return AppConstants.TRUSTED_HOST_REGEX.matches(h)
    }
}
