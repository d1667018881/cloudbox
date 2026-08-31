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
 * 登录流程（V4 修复：login.php 已于 2026-08-31 实测下线，pc/up.woozooo.com 均 404，
 * 旧 LanZouCloud-API 的 account.php 入口也已被 JS 跳转壳替代。现行协议，全部实测验证）：
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
