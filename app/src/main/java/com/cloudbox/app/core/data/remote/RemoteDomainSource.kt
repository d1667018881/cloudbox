package com.cloudbox.app.core.data.remote

import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.common.DomainUtils
import com.cloudbox.app.core.domain.model.LanzouDomainConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程域名配置源：启动时从 GitHub Gist（或任意 HTTPS URL）拉取 JSON。
 *
 * 为什么用 JSONObject 而非 Gson：配置结构简单固定，避免为一次拉取引入
 * serialization 插件依赖；解析失败/网络失败一律静默回落本地默认值，
 * 绝不让 App 因域名配置拉不到而无法启动。
 *
 * 远程 JSON 格式（示例，发布到 Gist 后把 URL 填入设置页）：
 * {
 *   "loginEntry": "https://up.woozooo.com/",
 *   "diskMain": "https://pc.woozooo.com/",
 *   "shareBase": "https://www.lanzou.com/",
 *   "uploadServer": "https://pc.woozooo.com/",
 *   "fallbackDomains": ["https://www.lanzoui.com/", "..."]
 * }
 */
@Singleton
class RemoteDomainSource @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    /**
     * 拉取并解析远程域名配置。
     * 防御性处理：字段缺失用默认值兜底；fallbackDomains 中的黑名单域名
     * （lanzous.com 被抢注）直接剔除。
     */
    suspend fun fetch(url: String): Result<LanzouDomainConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", AppConstants.DESKTOP_UA)
                .header("Accept", "application/json")
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())
                val fallback = json.optJSONArray(LanzouDomainConfig.KEY_FALLBACK)?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }?.filter { it.isNotBlank() && !DomainUtils.isForbidden(it) && it.startsWith("https://") }
                    ?: LanzouDomainConfig.DEFAULT.fallbackDomains

                val config = LanzouDomainConfig(
                    loginEntry = json.optString(LanzouDomainConfig.KEY_LOGIN, LanzouDomainConfig.DEFAULT.loginEntry),
                    diskMain = json.optString(LanzouDomainConfig.KEY_DISK, LanzouDomainConfig.DEFAULT.diskMain),
                    shareBase = json.optString(LanzouDomainConfig.KEY_SHARE, LanzouDomainConfig.DEFAULT.shareBase),
                    uploadServer = json.optString(LanzouDomainConfig.KEY_UPLOAD, LanzouDomainConfig.DEFAULT.uploadServer),
                    fallbackDomains = fallback
                )
                // #10 修复：四个主字段合法性校验（https + 域名白名单 + 黑名单）。
                // 旧实现原样接受远程值，被篡改的 Gist 可把全部 API 流量（含 phpdisk_info
                // Cookie）导向任意服务器 = 账号凭证窃取。任一主字段不合法则整份拒绝。
                listOf(config.loginEntry, config.diskMain, config.shareBase, config.uploadServer)
                    .forEach { if (!isTrustedDomain(it)) throw IllegalStateException("远程配置含非法域名: $it") }
                config
            }
        }
    }

    /**
     * 可信域名校验：强制 https + 非黑名单 + host 属于 woozooo 或 lanzou 系域名。
     * 注意：用户手动覆盖（设置页）不受此限制——那是用户自己的行为，风险自担。
     */
    private fun isTrustedDomain(value: String): Boolean {
        if (!value.startsWith("https://")) return false
        if (DomainUtils.isForbidden(value)) return false
        val host = runCatching { java.net.URI(value).host }.getOrNull() ?: return false
        val trusted = listOf("woozooo.com", "lanzou.com", "lanzoui.com", "lanzoup.com",
            "lanzoux.com", "lanzouo.com", "lanzouh.com", "lanzouu.com")
        return trusted.any { host == it || host.endsWith(".$it") }
    }
}
