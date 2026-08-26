package com.cloudbox.app.core.domain.model

/**
 * 蓝奏云域名动态配置。
 *
 * 背景：蓝奏云域名历史已漂移至少六轮（lanzous → lanzou → lanzoux → lanzoui → lanzoup → lanzouu），
 * 且 2025 年又出现 lanzouo.com / lanzouh.com 等新域（来源：zaxtyson/LanZouCloud-API PR#69，
 * 2025-10；tyut.tech 蓝奏云解析文章，2025）。域名随时可能再次漂移，
 * 因此全部请求必须经 [LanzouDomainConfig] 动态取域，禁止任何硬编码（工程要求 3）。
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
                "https://www.lanzoui.com/",
                "https://www.lanzoup.com/",
                "https://www.lanzoux.com/",
                // 以下为 2025 年确认活跃的新域（来源：zaxtyson PR#69 / tyut.tech 解析文章）
                "https://www.lanzouo.com/",
                "https://www.lanzouh.com/",
                "https://www.lanzouu.com/"
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
