package com.cloudbox.app.core.data.remote

import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.core.data.local.secure.AccountSecureStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
 *
 * 审查修复记录（CODE_REVIEW #2/#5/#6/#26/#27）：
 * - #6 线程安全：cache 读写全部 synchronized(lock)（OkHttp 线程池并发调用 + 协程切换）
 * - #2 持久化格式：OkHttp Cookie.toString() 输出小写属性（expires=/domain=/path=），
 *   旧实现用大写 "Domain=" 判断永不匹配，value 被属性串污染。改为结构化手工解析
 * - #27 分桶键统一：保存/恢复都按 cookie.domain（去前导点）分桶，避免跨子域漂移
 * - #26 过期过滤：loadForRequest 用 Cookie.matches(url) 过滤过期/域不匹配
 * - #5 新增 putCookie()：外部计算的 cookie（如 acw_sc__v2 反爬）写入 jar 统一管理，
 *   避免手动 Cookie 头被 OkHttp BridgeInterceptor 整体覆盖
 */
@Singleton
class CookiePersistenceJar @Inject constructor(
    private val accountStore: AccountSecureStore
) : CookieJar {

    /** 按 cookie.domain（去前导点）分桶的内存缓存（当前账号视角） */
    private val cache = HashMap<String, MutableList<Cookie>>()

    /** 所有 cache 读写必须持锁（OkHttp 线程池并发调用 saveFromResponse/loadForRequest） */
    private val lock = Any()

    /** 当前账号 uid（null = 未登录，不持久化） */
    @Volatile
    private var currentUid: String? = null

    /** 绑定账号槽位（切换账号 / 登录前预绑定） */
    fun switchAccount(uid: String?) {
        synchronized(lock) {
            currentUid = uid
            cache.clear()
            if (uid != null) {
                // 从加密存储恢复该账号的 Cookie
                for (line in accountStore.loadCookieLines(uid)) {
                    runCatching {
                        parseCookieLine(line)?.let { addCookieLocked(it) }
                    }
                }
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            for (cookie in cookies) {
                // 只持久化已知可信域的 Cookie：防止恶意/钓鱼域 Set-Cookie 污染加密存储。
                // 发送阶段 Cookie.matches 还会再过滤，但此处先拦截可避免持久化脏数据。
                if (!isTrustedCookieDomain(cookie.domain)) continue
                addCookieLocked(cookie)
            }
            persistLocked()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val host = url.host
            // Cookie 的 domain 常带前导点（如 .lanzou.com），且可能比请求 host 更短（父域 cookie），
            // 因此按"host 等于 key 或 host 是 key 的子域"匹配；再用 matches 过滤过期/域不匹配（#26）
            return cache
                .filterKeys { key -> host == key || host.endsWith(".$key") }
                .values
                .flatten()
                .filter { it.matches(url) }
                .toList()
        }
    }

    /** 当前账号是否持有指定名字的 Cookie（用于登录成功判定 / 过期检测） */
    fun hasCookie(name: String): Boolean = synchronized(lock) {
        cache.values.flatten().any { it.name == name }
    }

    /** 是否有 phpdisk_info（登录凭证） */
    fun isLoggedIn(): Boolean = hasCookie("phpdisk_info")

    /** 把外部计算的 cookie（如 acw_sc__v2）写入 jar，由统一 Cookie 头管理（#5） */
    fun putCookie(cookie: Cookie) {
        synchronized(lock) {
            addCookieLocked(cookie)
            persistLocked()
        }
    }

    /** 导出当前账号全部 Cookie（Set-Cookie 文本格式，每行一条） */
    fun export(): List<String> = synchronized(lock) {
        cache.values.flatten().map { it.toString() }
    }

    fun clearAll() {
        synchronized(lock) {
            cache.clear()
            val uid = currentUid ?: return
            accountStore.clearCookies(uid)
        }
    }

    /** 校验 Cookie 的 domain 是否属于已知可信域（后缀匹配），避免持久化恶意域 Cookie */
    private fun isTrustedCookieDomain(domain: String): Boolean {
        val d = domain.removePrefix(".").lowercase()
        return AppConstants.TRUSTED_SHARE_HOSTS.any { d == it || d.endsWith(".$it") }
    }

    private fun addCookieLocked(cookie: Cookie) {
        val key = cookie.domain.removePrefix(".")
        val bucket = cache.getOrPut(key) { mutableListOf() }
        // 按 name 去重覆盖（Set-Cookie 可能重复下发同名 Cookie）
        bucket.removeAll { it.name == cookie.name }
        bucket.add(cookie)
    }

    private fun persistLocked() {
        val uid = currentUid ?: return
        val lines = cache.values.flatten().map { it.toString() }
        accountStore.saveCookies(uid, lines)
    }

    /**
     * 解析一行 Set-Cookie 文本为 [Cookie]（#2 修复）。
     *
     * 为什么手工解析而不是 Cookie.parse：
     * 1. OkHttp 4.12 的 Cookie.toString() 输出**小写**属性（expires=/domain=/path=），
     *    旧实现按大写 "Domain=" 判断永不命中，导致 value 被整串属性污染；
     * 2. Cookie.parse 需要传入与 cookie domain 匹配的 URL，否则返回 null；
     *    手动导入的纯键值对（无 domain 属性）无法用 Cookie.parse 还原域。
     *
     * 规则：name/value 取第一段（到第一个 ';' 为止）；domain 属性大小写不敏感；
     * 无 domain 属性时按名字约定归属：phpdisk_info/ylogin → woozooo.com，其余 → lanzou.com。
     * 不解析 expires：恢复为 session cookie 语义（服务端自行校验 phpdisk_info 有效性）。
     */
    private fun parseCookieLine(line: String): Cookie? {
        val parts = line.split(';').map { it.trim() }
        val first = parts.firstOrNull() ?: return null
        val eq = first.indexOf('=')
        if (eq <= 0) return null
        val name = first.substring(0, eq).trim()
        val value = first.substring(eq + 1).trim()
        if (name.isEmpty()) return null

        val builder = Cookie.Builder().name(name).value(value).path("/")
        val domainAttr = parts.firstOrNull { it.startsWith("domain=", ignoreCase = true) }
        val domain = domainAttr?.substringAfter('=')?.trim()?.removePrefix(".")
            ?: if (name == "phpdisk_info" || name == "ylogin") "woozooo.com" else "lanzou.com"
        builder.domain(domain)
        return builder.build()
    }
}
