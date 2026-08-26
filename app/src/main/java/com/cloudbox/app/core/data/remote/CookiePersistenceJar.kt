package com.cloudbox.app.core.data.remote

import com.cloudbox.app.core.data.local.secure.AccountSecureStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cookie 持久化 Jar：拦截并持久化所有 Cookie 到 EncryptedSharedPreferences。
 *
 * 为什么不能只用内存 CookieJar：App 进程被杀后 Cookie 丢失 = 用户要重新登录；
 * phpdisk_info 有效期约 20 天，跨进程持久化才能享受完整有效期。
 *
 * 多账号支持：每个账号一个 Cookie 槽位（key = cookies_<uid>）。
 * 切换账号 = 调 [switchAccount] 把内存缓存换成目标账号的 Cookie。
 * 写回策略：任何新 Cookie 到达即整体持久化（简单可靠，Cookie 量很小）。
 */
@Singleton
class CookiePersistenceJar @Inject constructor(
    private val accountStore: AccountSecureStore
) : CookieJar {

    /** host -> cookies 的内存缓存（当前账号视角） */
    private val cache = HashMap<String, MutableList<Cookie>>()

    /** 当前账号 uid（null = 未登录，不持久化） */
    @Volatile
    private var currentUid: String? = null

    /** 登录成功后绑定账号槽位 */
    fun switchAccount(uid: String?) {
        currentUid = uid
        cache.clear()
        if (uid != null) {
            // 从加密存储恢复该账号的 Cookie
            for (line in accountStore.loadCookieLines(uid)) {
                runCatching {
                    val c = parseCookieLine(line) ?: return@runCatching
                    cache.getOrPut(c.domain.removePrefix(".")) { mutableListOf() }.add(c)
                }
            }
        }
    }

    /**
     * 解析一行 Set-Cookie 文本为 [Cookie]。
     *
     * 为什么手动处理"无 Domain 属性"的行：手动导入的 phpdisk_info 通常是纯键值对
     * （"phpdisk_info=xxx"），Cookie.parse 会按传入 URL 的 host 兜底（lanzou.com），
     * 而 phpdisk_info/ylogin 实际由 pc.woozooo.com 下发，域不对会导致请求不带 Cookie。
     * 因此按名字约定：woozooo 体系凭证归 woozooo.com，其余归 lanzou.com。
     */
    private fun parseCookieLine(line: String): Cookie? {
        if (line.contains("Domain=")) {
            // OkHttp 4.x：HttpUrl.get(String) 已废弃（ERROR 级），改用 toHttpUrl 扩展
            return Cookie.parse("https://www.lanzou.com/".toHttpUrl(), line)
        }
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val name = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim()
        val domain = if (name == "phpdisk_info" || name == "ylogin") "woozooo.com" else "lanzou.com"
        return Cookie.Builder()
            .name(name)
            .value(value)
            .domain(domain)
            .path("/")
            .expiresAt(System.currentTimeMillis() + 20L * 24 * 3600 * 1000)
            .build()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        // 只关心蓝奏云/woozooo 体系的 Cookie，避免污染第三方域
        val host = url.host
        if (!(host.contains("woozooo") || host.contains("lanzou"))) return

        val bucket = cache.getOrPut(host) { mutableListOf() }
        // 按 name 去重覆盖（Set-Cookie 可能重复下发同名 Cookie）
        for (cookie in cookies) {
            bucket.removeAll { it.name == cookie.name }
            bucket.add(cookie)
        }
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        // Cookie 的 domain 常带前导点（如 .lanzou.com），且可能比请求 host 更短（父域 cookie），
        // 因此按"host 等于 key 或 host 是 key 的子域"匹配
        return cache
            .filterKeys { key -> host == key || host.endsWith(".$key") }
            .values
            .flatten()
            .toList()
    }

    /** 当前账号是否持有指定名字的 Cookie（用于登录成功判定 / 过期检测） */
    fun hasCookie(name: String): Boolean =
        cache.values.flatten().any { it.name == name }

    /** 是否有 phpdisk_info（登录凭证） */
    fun isLoggedIn(): Boolean = hasCookie("phpdisk_info")

    private fun persist() {
        val uid = currentUid ?: return
        val lines = cache.values.flatten().map { it.toString() }
        accountStore.saveCookies(uid, lines)
    }

    /** 导出当前账号全部 Cookie（Set-Cookie 文本格式，每行一条） */
    fun export(): List<String> = cache.values.flatten().map { it.toString() }

    fun clearAll() {
        cache.clear()
        val uid = currentUid ?: return
        accountStore.clearCookies(uid)
    }
}
