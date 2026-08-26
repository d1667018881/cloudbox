package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.AccountInfo

/** 登录结果 */
sealed class LoginResult {
    data class Success(val account: AccountInfo) : LoginResult()
    data class Failure(val reason: String) : LoginResult()
}

/**
 * 认证仓库：登录 / Cookie 持久化 / 多账号槽位 / 过期检测。
 *
 * Cookie 过期判定（为什么是 18 天）：
 * phpdisk_info 有效期约 20 天（社区多方验证），到期后管理接口（doupload.php 等）
 * 一律 403。若等到 20 天才处理，用户正下载/上传到一半会失败；
 * 因此 lastActiveAt 距今 >18 天就触发重登流程，留 2 天缓冲。
 */
interface AuthRepository {

    /** 当前登录账号（null = 未登录） */
    val currentAccount: kotlinx.coroutines.flow.Flow<AccountInfo?>

    /** 全部已保存账号（多账号槽位列表） */
    suspend fun allAccounts(): List<AccountInfo>

    /** 账号密码登录；成功即持久化 Cookie 并切换为当前账号
     *  @param rememberPwd 是否加密保存密码（用于 >18 天后的静默重登） */
    suspend fun login(uid: String, pwd: String, rememberPwd: Boolean = true): LoginResult

    /** 从剪贴板手动导入 phpdisk_info Cookie 串（离线登录场景） */
    suspend fun importCookie(uid: String, cookieHeader: String): LoginResult

    /** 一键切换账号（切换 Cookie 槽位） */
    suspend fun switchAccount(uid: String): Boolean

    /** 退出登录（清空该账号 Cookie 槽位） */
    suspend fun logout(uid: String)

    /** 启动时调用：检测过期并在允许时用保存账密静默重登 */
    suspend fun ensureSession(): AccountInfo?

    /** 导出当前账号 Cookie（备份用，Set-Cookie 文本格式） */
    suspend fun exportCookies(uid: String): String?

    /** 恢复备份的 Cookie 到指定账号 */
    suspend fun restoreCookies(uid: String, cookieText: String): Boolean
}
