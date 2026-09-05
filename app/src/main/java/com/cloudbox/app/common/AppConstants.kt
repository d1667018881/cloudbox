package com.cloudbox.app.common

import com.cloudbox.app.core.domain.model.LanzouDomainConfig

/**
 * 全局常量集中地。
 *
 * 为什么单独一个文件：域名池、UA、正则这些"易变事实"集中管理，
 * 域名漂移/接口变更时只改这里（或远程配置），避免散落各处。
 */
object AppConstants {

    /** 桌面 Chrome UA：蓝奏云对手机 UA 隐藏 APK 等格式的下载入口（判定依据就是 UA），
     *  必须全程伪装桌面 UA 才能拿到下载入口（需求规格 7 节） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** Retrofit baseUrl 占位 host。
     *  为什么用占位符：Retrofit 创建时绑定 baseUrl，而域名是动态可配置的。
     *  解法是让 [com.cloudbox.app.core.data.remote.LanzouDomainInterceptor]
     *  在请求发出前按路径角色重写 host——这样域名无论怎么变都无需重建 Retrofit。 */
    const val PLACEHOLDER_HOST = "lz.dynamic.invalid"

    /** 直链域黑名单：lanzous.com 已被第三方抢注（解析到不良站点），必须拦截 */
    val FORBIDDEN_DOMAINS = setOf("lanzous.com", "www.lanzous.com")

    // ==================== 账号中心（V4 登录协议） ====================
    // login.php 已于 2026-08-31 实测下线（pc/up.woozooo.com 404），登录统一走账号中心。
    // 协议（AuthRepositoryImpl KDoc 有完整流程）：GET 登录页解 acw_sc__v2 挑战 →
    // POST 同一 URL（task=uselogin&username&password&ref=pc.woozooo.com，AJAX 头）→
    // zt=1 时 msgs 为中转鉴权 URL → GET 它收集 phpdisk_info Cookie。

    /** 账号中心登录页（GET 入口，同时是 POST 提交目标） */
    const val ACCOUNT_CENTER_LOGIN_URL =
        "https://accounts.woozooo.com/accounts.php?action=login&ref=pc.woozooo.com"

    /** 凭证 AJAX 提交地址（与登录页同 URL；独立常量便于日后分离时不混淆语义） */
    const val ACCOUNT_CENTER_SUBMIT_URL = ACCOUNT_CENTER_LOGIN_URL

    /** 提交 form 中 ref 字段的值（页面 JS 实证：ref=pc.woozooo.com） */
    const val ACCOUNT_CENTER_REF_HOST = "pc.woozooo.com"

    /** 账号中心根地址（挑战 Cookie 写域时取 host 用） */
    const val ACCOUNT_CENTER_BASE = "https://accounts.woozooo.com"

    /** 受信任的分享/接口域名后缀（与 RemoteDomainSource.isTrustedDomain 同步维护）。
     *  用于：1) Cookie 持久化域过滤；2) 远程配置校验等需要"完整枚举"的场景。
     *  注意：链接识别不走此枚举（蓝奏云单字母变体域名太多，枚举必漏），
     *  统一走 [TRUSTED_HOST_REGEX]（见 DomainUtils.isTrustedShareHost）。 */
    val TRUSTED_SHARE_HOSTS = setOf(
        "woozooo.com",
        "lanzou.com",
        "lanzoui.com",
        "lanzoup.com",
        "lanzoux.com",
        "lanzouo.com",
        "lanzouh.com",
        "lanzouu.com"
    )

    /** 受信任 host 匹配正则：lanzou + 可选单个字母后缀（lanzouw/lanzouq/lanzoum 等
     *  全部单字母变体均覆盖，蓝奏云换域名无需改代码）+ 多级子域 + woozooo.com。
     *  钓鱼域天然排除：lanzoucloud.com / evil-lanzou.com 等"lanzou 后跟多字母"的域
     *  无法匹配（[a-z]? 最多 1 个字母且必须紧跟 .com）。lanzous.com 由黑名单单独拦截。 */
    val TRUSTED_HOST_REGEX = Regex("""^(?:[a-z0-9-]+\.)*(?:lanzou[a-z]?|woozooo)\.com$""")

    /** 识别"分享链接"的正则：lanzou + 可选单字母变体域名（覆盖全部已知/未来变体），
     *  防钓鱼域结构上排除（同 TRUSTED_HOST_REGEX 说明）。实际判定仍结合
     *  DomainUtils.isTrustedShareHost 二次校验。 */
    val SHARE_URL_REGEX = Regex(
        """https?://(?:[a-z0-9-]+\.)*(?:lanzou[a-z]?|woozooo)\.com/[a-zA-Z0-9]+/?"""
    )

    /** 从 URL 中提取分享 ID（最后一层路径段，如 lanzou.com/i5g8y1a 的 i5g8y1a） */
    val SHARE_ID_REGEX = Regex("""/([a-zA-Z0-9]+)/?$""")

    /** 超时（毫秒）：需求规格要求 30s */
    const val TIMEOUT_CONNECT_MS = 30_000L
    const val TIMEOUT_READ_MS = 30_000L
    const val TIMEOUT_WRITE_MS = 30_000L

    /** 重试策略：最多重试 3 次，指数退避基数 2s */
    const val MAX_RETRIES = 3
    const val RETRY_BASE_DELAY_MS = 2_000L

    /** phpdisk_info 有效期约 20 天；超过 18 天视为"即将过期"触发重登（留 2 天余量，
     *  避免下载/上传中途 Cookie 失效导致任务失败） */
    const val COOKIE_MAX_AGE_MS = 20L * 24 * 3600 * 1000
    const val COOKIE_RELOGIN_THRESHOLD_MS = 18L * 24 * 3600 * 1000

    /** 远程域名配置默认拉取地址（可被用户覆盖）。
     *  提供一个示例 Gist；若未发布，App 会静默回落到本地默认值 */
    const val DEFAULT_REMOTE_DOMAIN_URL = "https://gist.githubusercontent.com/example/lanzou-domains.json"

    /** 风控相关：同 UA/IP 对同一分享页 7 天访问上限约 5 次（超限临时拉黑），
     *  因此批量解析必须加 1-3s 随机延时（需求规格 7 节） */
    const val SHARE_PAGE_RATE_LIMIT_WINDOW_MS = 7L * 24 * 3600 * 1000
    const val BATCH_DELAY_MIN_MS = 1_000L
    const val BATCH_DELAY_MAX_MS = 3_000L

    /** 上传：免费用户单文件上限 100MB（来源：爱企查/php中文网 2026 资料，多来源印证）。
     *  注意：不写死任何"登录后自动放宽"逻辑——会员额度社区传闻 200M-210M 无权威佐证
     *  （需求规格 4 节硬性要求） */
    const val FREE_FILE_LIMIT_BYTES = 100L * 1024 * 1024

    /** 分卷单卷上限 95MB：留 5MB 余量，避免贴线被拒（需求规格要求） */
    const val SPLIT_VOLUME_BYTES = 95L * 1024 * 1024

    /** 配置是否已初始化的 DataStore key */
    const val DS_KEY_DOMAIN_INIT = "domain_init"
}
