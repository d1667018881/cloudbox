package com.cloudbox.app.core.data.repository

import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.core.data.local.secure.AccountSecureStore
import com.cloudbox.app.core.data.remote.CookiePersistenceJar
import com.cloudbox.app.core.data.remote.LanzouApiClient
import com.cloudbox.app.core.domain.model.AccountInfo
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.core.domain.repository.LoginResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 认证仓库实现。
 *
 * 登录流程（V7 改版）：
 * 【主通道】原版 App 协议（逆向 login.lua:273，2026-09-07 实测存活）
 *   1. GET  https://pc.woozooo.com/mlogin.php          （建会话，服务端下发 PHPSESSID）
 *   2. POST 同 URL，form: task=3&uid&pwd&setSessionId=&setSig=&setScene=&setToken=&formhash=
 *   3. 响应 {"zt":1,...} 成功（凭证在 Set-Cookie）/ {"zt":0,"info":"没有用户"} 失败
 *   4. 成功判定 = CookieJar.isLoggedIn()（phpdisk_info 到手）
 * 【回落通道】仅在主通道不可用（非账号密码错误）时走账号中心 accounts.php：
 * 1. GET accounts.woozooo.com/accounts.php?action=login&ref=pc.woozooo.com
 *    —— 首次访问返回 acw_sc__v2 JS 挑战页（var arg1='…'），本地计算挑战值
 *    写入 CookieJar 后重 GET（[AcwScV2]，算法与 LanZouCloud-API 原版逐行一致）
 * 2. POST 同一 URL，form: task=uselogin&username&password&ref=pc.woozooo.com
 *    （页面 JS `var task ='uselogin'` 实证；X-Requested-With: XMLHttpRequest）
 *    —— 响应 JSON {"zt":0,"msgs":"错误文案"}（如"用户名不正确"）；
 *    挑战 cookie 缺失/过期时响应也会是挑战页，需解挑战后重试一次
 * 3. zt=1 时 msgs = 中转鉴权 URL，GET 它（OkHttp 自动跟随重定向链），
 *    链上由 pc.woozooo.com Set-Cookie 下发 phpdisk_info → CookieJar 收集
 * 4. 成功判定 = CookieJar.isLoggedIn()（phpdisk_info 到手），与旧流程一致
 *
 * 静默重登（ensureSession）：phpdisk_info 有效期约 20 天，启动时若
 * lastActiveAt 距今 >18 天（留 2 天缓冲，避免任务中途失效），
 * 且该账号保存过密码且允许自动重登，则自动重新登录一次。
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val apiClient: LanzouApiClient,
    private val accountStore: AccountSecureStore,
    private val cookieJar: CookiePersistenceJar
) : AuthRepository {

    private val _currentAccount = MutableStateFlow<AccountInfo?>(null)
    override val currentAccount: StateFlow<AccountInfo?> = _currentAccount.asStateFlow()

    init {
        // 启动恢复：从安全存储恢复当前账号视角的 Cookie 槽位
        val uid = accountStore.currentUid()
        if (uid != null) {
            cookieJar.switchAccount(uid)
            _currentAccount.value = accountStore.accountInfo(uid)
        }
    }

    override suspend fun allAccounts(): List<AccountInfo> =
        accountStore.allUids().map { accountStore.accountInfo(it) }

    override suspend fun login(uid: String, pwd: String, rememberPwd: Boolean): LoginResult =
        withContext(Dispatchers.IO) {
            // 槽位预绑定（V2 #1 修复，保留）：发请求【前】先绑定槽位，
            // 让响应期的 saveFromResponse→persist() 把 phpdisk_info 落盘到【正确账号】槽位。
            val prevUid = accountStore.currentUid()
            accountStore.saveUid(uid)
            accountStore.setCurrentUid(uid)
            cookieJar.switchAccount(uid)
            try {
                // V7 主通道：原版 mlogin.php（task=3，2026-09-07 实测存活）
                when (val outcome = loginViaMlogin(uid, pwd)) {
                    is MloginOutcome.Ok -> {
                        fetchCloudUid(uid)
                        if (rememberPwd) accountStore.savePassword(uid, pwd)
                        accountStore.touchActive(uid)
                        _currentAccount.value = outcome.info
                        LoginResult.Success(outcome.info)
                    }
                    is MloginOutcome.Rejected -> {
                        // 服务端明确拒绝（账号不存在 / 密码错误）：不必再试回落通道
                        rollbackTo(prevUid, uid)
                        LoginResult.Failure(outcome.reason)
                    }
                    is MloginOutcome.Unavailable -> {
                        // 主通道不可用（端点变更 / 被挑战页拦截 / 未下发凭证）→ 回落账号中心
                        loginViaAccountCenter(uid, pwd, rememberPwd, prevUid)
                    }
                }
            } catch (e: Exception) {
                rollbackTo(prevUid, uid)
                LoginResult.Failure(e.message ?: "网络异常")
            }
        }

    /**
     * 原版登录协议（逆向 login.lua:273 / home_func.lua:891，2026-09-07 实测有效）：
     *   ① GET  pc.woozooo.com/mlogin.php          → 服务端下发 PHPSESSID
     *   ② POST 同 URL，form: task=3&uid=&pwd=&setSessionId=&setSig=&setScene=&setToken=&formhash=
     *   ③ 响应 JSON：zt=1 成功（凭证在响应头 Set-Cookie，原版取回调第三参 a3）；
     *      zt=0 时 info 为中文原因（实测 "没有用户"）。
     *
     * 为什么改回原版通道：账号中心 accounts.php 长期返回 acw_sc__v2 挑战页，
     * 解完挑战仍可能停留在挑战页，登录拿不到 phpdisk_info —— 表现为"能登录进
     * 网盘界面但上传永远失败（zt=9 login not）"。
     */
    private fun loginViaMlogin(username: String, password: String): MloginOutcome {
        val url = AppConstants.MLOGIN_URL
        return try {
            // ① 建会话
            runCatching {
                apiClient.okHttpClient.newCall(
                    Request.Builder().url(url).header("User-Agent", AppConstants.DESKTOP_UA).build()
                ).execute().close()
            }
            // ② 提交凭证（密码为明文，与原版一致——原版未做 md5）
            val form = FormBody.Builder()
                .add("task", "3")
                .add("uid", username)
                .add("pwd", password)
                .add("setSessionId", "")
                .add("setSig", "")
                .add("setScene", "")
                .add("setToken", "")
                .add("formhash", "")
                .build()
            val body = apiClient.okHttpClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", AppConstants.DESKTOP_UA)
                    .header("Referer", url)
                    .post(form)
                    .build()
            ).execute().use { it.body?.string().orEmpty() }

            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return MloginOutcome.Unavailable("mlogin 未返回 JSON（端点可能已变更）")

            if (json.optInt("zt") == 1) {
                // 凭证由响应头 Set-Cookie 下发，OkHttp CookieJar 已自动收集。
                // 极少数情况 POST 只回 zt=1 而不带 phpdisk_info：补一次网盘首页请求兜底。
                if (!cookieJar.isLoggedIn()) {
                    runCatching {
                        apiClient.okHttpClient.newCall(
                            Request.Builder()
                                .url("${AppConstants.PC_WOOZOOO}/mydisk.php?item=files&action=index")
                                .header("User-Agent", AppConstants.DESKTOP_UA)
                                .build()
                        ).execute().close()
                    }
                }
                if (cookieJar.isLoggedIn()) {
                    MloginOutcome.Ok(accountStore.accountInfo(username))
                } else {
                    MloginOutcome.Unavailable("mlogin 返回成功但未取得 phpdisk_info")
                }
            } else {
                MloginOutcome.Rejected(json.optString("info").ifBlank { "登录失败" })
            }
        } catch (e: Exception) {
            MloginOutcome.Unavailable(e.message ?: "网络异常")
        }
    }

    /** 登录主通道的三种结局（区分"账号密码错"与"协议不可用"，决定是否回落） */
    private sealed class MloginOutcome {
        data class Ok(val info: AccountInfo) : MloginOutcome()
        data class Rejected(val reason: String) : MloginOutcome()
        data class Unavailable(val reason: String) : MloginOutcome()
    }

    /**
     * 回落通道：账号中心 accounts.woozooo.com（task=uselogin + acw_sc__v2）。
     * 仅在主通道 [loginViaMlogin] 返回 [MloginOutcome.Unavailable] 时调用。
     */
    private suspend fun loginViaAccountCenter(
        uid: String,
        pwd: String,
        rememberPwd: Boolean,
        prevUid: String?
    ): LoginResult = withContext(Dispatchers.IO) {
        try {
            // V4：登录迁移到统一账号中心（login.php 已实测下线，协议见类 KDoc）
                // ① 打开登录页：首次访问触发 acw_sc__v2 挑战，helper 内部自动解挑战重试
                getWithAcwChallenge(AppConstants.ACCOUNT_CENTER_LOGIN_URL)
                // ② AJAX 提交凭证（挑战 cookie 过期时 helper 自动解挑战重试一次）
                val body = postLogin(uid, pwd)
                val json = runCatching { JSONObject(body) }.getOrNull()

                when {
                    // ③ 成功：msgs 为中转鉴权 URL，GET 它跟完重定向链收集 phpdisk_info
                    json?.optInt("zt") == 1 -> {
                        val relayUrl = json.optString("msgs")
                        if (relayUrl.startsWith("http")) {
                            // 中转链本身也可能被挑战，同样走 helper
                            getWithAcwChallenge(relayUrl)
                        }
                        if (cookieJar.isLoggedIn()) {
                            fetchCloudUid(uid)
                            if (rememberPwd) accountStore.savePassword(uid, pwd)
                            accountStore.touchActive(uid)
                            val info = accountStore.accountInfo(uid)
                            _currentAccount.value = info
                            LoginResult.Success(info)
                        } else {
                            rollbackTo(prevUid, uid)
                            LoginResult.Failure("登录跳转未获取到凭证（可尝试 Cookie 导入）")
                        }
                    }
                    // 失败：zt=0 时 msgs 为服务端中文错误文案（"用户名不正确"/"密码错误"等）
                    json != null -> {
                        rollbackTo(prevUid, uid)
                        val reason = json.optString("msgs").ifBlank { "登录失败" }
                        LoginResult.Failure(reason)
                    }
                    // 非 JSON（重试后仍是 HTML）：按页面文本提取错误
                    else -> {
                        rollbackTo(prevUid, uid)
                        val text = runCatching { Jsoup.parse(body).text() }.getOrDefault(body)
                        val reason = extractLoginError(text)
                            ?: "登录失败（服务端响应异常，可尝试 Cookie 导入）"
                        LoginResult.Failure(reason)
                    }
                }
            } catch (e: Exception) {
                // 网络异常同样回滚，避免误切账号
                rollbackTo(prevUid, uid)
                LoginResult.Failure(e.message ?: "网络异常")
            }
        }

    // ==================== 账号中心协议 helpers（V4） ====================

    /** GET 指定 URL；若响应是 acw_sc__v2 挑战页则解挑战后重试一次，返回最终响应体 */
    private fun getWithAcwChallenge(url: String): String {
        var body = httpGet(url)
        if (solveAcwChallengeIfPresent(body, url)) body = httpGet(url)
        return body
    }

    /** POST 登录凭证；若响应是挑战页（挑战 cookie 缺失/过期）则解挑战后重试一次 */
    private fun postLogin(username: String, password: String): String {
        var body = httpPostLogin(username, password)
        if (solveAcwChallengeIfPresent(body, AppConstants.ACCOUNT_CENTER_SUBMIT_URL)) {
            body = httpPostLogin(username, password)
        }
        return body
    }

    private fun httpGet(url: String): String =
        apiClient.okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            .use { it.body?.string().orEmpty() }

    /**
     * 登录后提取网盘【数字 uid】并落盘（原版 home_func.lua:2372：
     * `uid后缀 = "?uid=" .. 页面里匹配到的 index&u=(.-)'`）。
     *
     * 该 uid 会被 [com.cloudbox.app.core.data.remote.LanzouUidInterceptor] 拼到所有
     * doupload.php 请求上。取不到不影响登录结果——拦截器在无 uid 时原样放行。
     */
    private fun fetchCloudUid(uid: String) {
        val html = runCatching {
            apiClient.okHttpClient.newCall(
                Request.Builder()
                    .url("${AppConstants.PC_WOOZOOO}/mydisk.php")
                    .header("User-Agent", AppConstants.DESKTOP_UA)
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return
        val cloudUid = Regex("""index&u=(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]u=(\d+)""").find(html)?.groupValues?.get(1)
            ?: return
        accountStore.saveCloudUid(uid, cloudUid)
    }

    private fun httpPostLogin(username: String, password: String): String {
        val form = FormBody.Builder()
            .add("task", "uselogin")   // 页面 JS var task ='uselogin'（实测，勿改 login）
            .add("username", username)
            .add("password", password)
            .add("ref", AppConstants.ACCOUNT_CENTER_REF_HOST)
            .build()
        return apiClient.okHttpClient.newCall(
            Request.Builder()
                .url(AppConstants.ACCOUNT_CENTER_SUBMIT_URL)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(form)
                .build()
        ).execute().use { it.body?.string().orEmpty() }
    }

    /**
     * 若 body 是 acw_sc__v2 挑战页（var arg1='…'），计算挑战值写入 CookieJar（按目标
     * host 建域 cookie），返回 true 表示调用方应重试一次。
     * 写入走 putCookie 而非手动 header：OkHttp BridgeInterceptor 会整体替换手动
     * Cookie 头（DirectLinkRepositoryImpl 同款教训，见 V2 #5）。
     */
    private fun solveAcwChallengeIfPresent(body: String, url: String): Boolean {
        if (!body.contains("acw_sc__v2")) return false
        val value = AcwScV2.compute(body)?.substringAfter('=') ?: return false
        val host = runCatching { java.net.URI(url).host }.getOrNull()
            ?: java.net.URI(AppConstants.ACCOUNT_CENTER_BASE).host ?: return false
        val cookie = Cookie.Builder()
            .name("acw_sc__v2")
            .value(value)
            .domain(host.removePrefix("www."))
            .path("/")
            .build()
        apiClient.cookieJar.putCookie(cookie)
        return true
    }

    /** 登录失败/异常时的槽位回滚：还原到原当前账号（首次登录则清空） */
    private fun rollbackTo(prevUid: String?, attemptedUid: String) {
        if (prevUid != null && prevUid != attemptedUid) {
            accountStore.setCurrentUid(prevUid)
            cookieJar.switchAccount(prevUid)
            _currentAccount.value = accountStore.accountInfo(prevUid)
        } else {
            accountStore.clearCurrentUid()
            cookieJar.switchAccount(null)
            _currentAccount.value = null
        }
    }

    override suspend fun importCookie(uid: String, cookieHeader: String): LoginResult =
        withContext(Dispatchers.IO) {
            // 从剪贴板导入 phpdisk_info 字符串（可能形如 "phpdisk_info=xxx; ylogin=xxx" 或纯单条）
            val lines = cookieHeader
                .split(';', '\n')
                .map { it.trim() }
                .filter { it.startsWith("phpdisk_info=") || it.startsWith("ylogin=") }

            if (lines.isEmpty()) {
                return@withContext LoginResult.Failure("剪贴板中没有找到 phpdisk_info 或 ylogin")
            }
            accountStore.saveUid(uid)
            accountStore.setCurrentUid(uid)
            cookieJar.switchAccount(uid)
            // 直接把导入的 Cookie 文本落盘（不走 Cookie.parse，保持原样）
            accountStore.saveCookies(uid, lines)
            cookieJar.switchAccount(uid) // 重新加载
            fetchCloudUid(uid) // Cookie 导入路径同样要拿数字 uid（否则 doupload 缺 ?uid=）
            val info = accountStore.accountInfo(uid)
            _currentAccount.value = info
            LoginResult.Success(info)
        }

    override suspend fun switchAccount(uid: String): Boolean {
        if (uid !in accountStore.allUids()) return false
        accountStore.setCurrentUid(uid)
        cookieJar.switchAccount(uid)
        _currentAccount.value = accountStore.accountInfo(uid)
        return true
    }

    override suspend fun logout(uid: String) {
        accountStore.removeUid(uid)
        if (accountStore.currentUid() == null) {
            cookieJar.switchAccount(null)
            _currentAccount.value = null
        }
    }

    override suspend fun ensureSession(): AccountInfo? = withContext(Dispatchers.IO) {
        val current = _currentAccount.value ?: return@withContext null
        val uid = current.uid
        val lastActive = accountStore.lastActiveAt(uid)
        val expired = System.currentTimeMillis() - lastActive > AppConstants.COOKIE_RELOGIN_THRESHOLD_MS

        if (!expired) {
            // 未过期：只刷新活跃时间
            accountStore.touchActive(uid)
            return@withContext current
        }
        // 已超过 18 天：Cookie 大概率已失效（phpdisk_info 有效期约 20 天）
        if (current.autoRelogin) {
            val pwd = accountStore.loadPassword(uid)
            if (!pwd.isNullOrEmpty()) {
                val result = login(uid, pwd, rememberPwd = true)
                if (result is LoginResult.Success) return@withContext result.account
            }
        }
        // 无法静默重登：清掉过期 Cookie，返回 null 让 UI 提示重新登录
        cookieJar.clearAll()
        accountStore.setCurrentUid(uid) // 保留账号槽位，仅清凭证
        _currentAccount.value = null
        null
    }

    override suspend fun exportCookies(uid: String): String? {
        // 确保读取的是该账号槽位
        cookieJar.switchAccount(uid)
        return cookieJar.export().joinToString("\n").ifEmpty { null }.also {
            // 切回原账号
            accountStore.currentUid()?.let { cookieJar.switchAccount(it) }
        }
    }

    override suspend fun restoreCookies(uid: String, cookieText: String): Boolean {
        val lines = cookieText.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        accountStore.saveUid(uid)
        accountStore.saveCookies(uid, lines)
        if (accountStore.currentUid() == uid) cookieJar.switchAccount(uid)
        return true
    }

    /** 从登录失败页面文本中提取错误原因 */
    private fun extractLoginError(text: String): String? {
        val keywords = listOf(
            "密码错误" to "密码错误",
            "账号或密码错误" to "账号或密码错误",
            "用户不存在" to "用户不存在",
            "用户名不存在" to "用户名不存在",
            "登录失败" to "登录失败",
            "验证码" to "需要验证码（请稍后重试或更换网络）"
        )
        return keywords.firstOrNull { text.contains(it.first) }?.second
    }
}
