package com.cloudbox.app.core.domain.model

/**
 * 应用更新信息（对齐原版 `about.lua` 的检查更新结果）。
 *
 * ⚠️ 原版更新源是微软 AppCenter（`Stardew/Lancloud`），该服务于 2025-03-31 停用
 * （原版 `ty_core.lua` 也内置了"更新服务即将停用"的弹窗）。所以这里**不照搬**端点，
 * 改连 CloudBox 自己的 GitHub Releases —— CI 每次构建都会发一个带 APK 的 Release。
 */
data class AppUpdate(
    /** 远端 versionCode（从 tag `v0.1.<run>` 的末段解析；CI 用 run_number 作 versionCode） */
    val versionCode: Int,
    /** 远端版本名，如 `0.1.166` */
    val versionName: String,
    /** 更新说明（Release body） */
    val releaseNotes: String,
    /** APK 直链 */
    val downloadUrl: String,
    val publishedAt: String? = null
)
