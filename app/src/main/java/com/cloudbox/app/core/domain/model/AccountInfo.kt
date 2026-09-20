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
 * @param staleReason 保存的密码已被服务端拒绝的原因（如改密后 "没有用户"）。
 *
 *   ⚠️ 有了它，UI 才能把"闪一下登录页又跳回主页"这种含糊现象变成一句可读的提示。
 *   旧实现把这个失败**完全丢弃**：静默重登返回 Failure，没人看，用户只看到
 *   账号像是还在，却什么都做不了。
 */
data class AccountInfo(
    val uid: String,
    val lastActiveAt: Long = 0L,
    val autoRelogin: Boolean = true,
    val staleReason: String? = null
)
