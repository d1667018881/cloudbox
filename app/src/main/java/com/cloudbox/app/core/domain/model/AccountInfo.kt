package com.cloudbox.app.core.domain.model

/**
 * 账号信息（多账号槽位模型）。
 *
 * 设计说明：蓝奏云 Cookie（phpdisk_info）与账号绑定，每个账号需要独立的 Cookie 槽位。
 * 密码明文存于 EncryptedSharedPreferences（Keystore 加密），内存中不保留明文。
 *
 * V32：移除 `nickname` 字段。
 * 它原计划用于展示"登录后从页面提取的显示名"，但构造点
 * （AccountSecureStore.accountInfo）从未给它赋值，全仓库也无任何读取点，
 * 实际恒为默认值 —— 属于设计残留，不是未完成功能。
 *
 * @param uid      账号（登录表单 uid）
 * @param lastActiveAt 最近活跃时间戳（毫秒）。phpdisk_info 有效期约 20 天，
 *                     超过 18 天就应触发静默重登或提示（详见 AuthRepository 注释）
 * @param autoRelogin 是否允许用保存的账密静默重登
 */
data class AccountInfo(
    val uid: String,
    val lastActiveAt: Long = 0L,
    val autoRelogin: Boolean = true
)
