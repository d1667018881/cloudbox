package com.cloudbox.app.core.domain.model

/**
 * 账号信息（多账号槽位模型）。
 *
 * 设计说明：蓝奏云 Cookie（phpdisk_info）与账号绑定，每个账号需要独立的 Cookie 槽位。
 * 密码明文存于 EncryptedSharedPreferences（Keystore 加密），内存中不保留明文。
 *
 * @param uid      账号（登录表单 uid）
 * @param nickname 显示名（登录成功后从页面提取，可为空）
 * @param lastActiveAt 最近活跃时间戳（毫秒）。phpdisk_info 有效期约 20 天，
 *                     超过 18 天就应触发静默重登或提示（详见 AuthRepository 注释）
 * @param autoRelogin 是否允许用保存的账密静默重登
 */
data class AccountInfo(
    val uid: String,
    val nickname: String = "",
    val lastActiveAt: Long = 0L,
    val autoRelogin: Boolean = true
)
