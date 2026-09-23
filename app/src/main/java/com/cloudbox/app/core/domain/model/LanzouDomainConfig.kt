package com.cloudbox.app.core.domain.model

/**
 * 蓝奏云域名动态配置。
 *
 * 背景：蓝奏云的**分享域**历史已漂移多轮（lanzous → lanzou → lanzoux → lanzoui →
 * lanzoup），随时可能再次漂移，所以全部请求必须经 [LanzouDomainConfig] 动态取域，
 * 禁止硬编码（工程要求 3）。
 *
 * ⚠️ 不要把「第三方项目列出的域名」直接当官方域名照抄（2026-09-23 更正）：
 * 此前 fallbackDomains 里的 lanzouo.com / lanzouh.com / lanzouu.com 来自
 * zaxtyson/LanZouCloud-API 的一个 PR，**未经任何验证就进了池子**。实测结果：
 *   - lanzouh.com / lanzouu.com：**DNS 根本不存在**（纯属无效配置）
 *   - lanzouo.com：能解析，但 443 直接 ConnectionRefused，首页无任何蓝奏云特征
 * 三者均已移除。今后要新增域名，务必先按下面的【验证方法】过一遍。
 *
 * ── 验证方法（2026-09-23 实测有效）──
 * 1. 首页特征：lanzou.com / lanzoui.com 的 `<title>` 都是「蓝奏·云存储」；
 * 2. 同源性：官方轮换域解析到同一批国内 IP（如 101.227.20.x / 47.91.203.57）；
 * 3. 反例参照：被抢注的 lanzous.com 解析到**境外** IP（172.234.x）且首页无
 *    蓝奏云内容 —— 这正是它必须留在黑名单里的原因。
 *
 * ⚠️ 另一条更根本的原则：分享域的**权威来源是服务端下发的 `is_newd`**
 * （见 LanzouApiService 的分享链接拼装），本文件的 fallbackDomains 只是
 * 服务端没给出时的兜底。兜底永远追不上轮换，别指望靠它覆盖所有域。
 *
 * @param loginEntry    登录入口域（woozooo 体系）
 * @param diskMain      网盘管理主域（文件列表 / 文件管理操作都走这里）
 * @param shareBase     分享页基址（分享链接解析 / 直链 ajaxm.php）
 * @param uploadServer  上传接口域
 * @param fallbackDomains 备用域名池（分享域漂移时依次尝试）
 */
data class LanzouDomainConfig(
    val loginEntry: String,
    val diskMain: String,
    val shareBase: String,
    val uploadServer: String,
    val fallbackDomains: List<String>
) {
    companion object {
        /**
         * 默认配置。
         * 注意：严禁把 lanzous.com 放入池中——该域名已被第三方抢注，解析到不良站点
         * （来源：需求规格 + 31du.cn 域名更换文章，2025）。
         */
        val DEFAULT = LanzouDomainConfig(
            loginEntry = "https://up.woozooo.com/",
            diskMain = "https://pc.woozooo.com/",
            shareBase = "https://www.lanzou.com/",
            uploadServer = "https://pc.woozooo.com/",
            fallbackDomains = listOf(
                // 只放**实测验证过**的官方轮换域（验证方法与反例见文件头注释）。
                "https://www.lanzoui.com/",
                "https://www.lanzoup.com/",
                "https://www.lanzoux.com/"
            )
        )

        /** 远程 JSON 的 key 名，与 GitHub Gist 发布的格式保持一致 */
        const val KEY_LOGIN = "loginEntry"
        const val KEY_DISK = "diskMain"
        const val KEY_SHARE = "shareBase"
        const val KEY_UPLOAD = "uploadServer"
        const val KEY_FALLBACK = "fallbackDomains"
    }
}
