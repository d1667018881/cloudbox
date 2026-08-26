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
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 认证仓库实现。
 *
 * 登录成功判定（为什么看 Cookie 而不是 HTTP 状态码）：
 * 蓝奏云 login.php 无论成败都返回 200（失败是带错误文案的 HTML 页面，
 * 成功是 302 重定向）。唯一可靠的判定信号是 Set-Cookie 是否包含 phpdisk_info
 * （身份凭证，有效期约 20 天）。因此判定逻辑 = CookieJar.isLoggedIn()。
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
            try {
                val resp = apiClient.apiService.login(uid = uid, pwd = pwd)
                if (cookieJar.isLoggedIn()) {
                    // 登录成功：写入槽位并切换
                    accountStore.saveUid(uid)
                    if (rememberPwd) accountStore.savePassword(uid, pwd)
                    accountStore.setCurrentUid(uid)
                    cookieJar.switchAccount(uid)
                    val info = accountStore.accountInfo(uid)
                    _currentAccount.value = info
                    LoginResult.Success(info)
                } else {
                    // 失败：页面 HTML 里包含错误文案，用 Jsoup 提取
                    val html = resp.body()?.string().orEmpty()
                    val text = runCatching { Jsoup.parse(html).text() }.getOrDefault(html)
                    val reason = extractLoginError(text) ?: "登录失败（未获取到身份凭证）"
                    LoginResult.Failure(reason)
                }
            } catch (e: Exception) {
                LoginResult.Failure(e.message ?: "网络异常")
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
