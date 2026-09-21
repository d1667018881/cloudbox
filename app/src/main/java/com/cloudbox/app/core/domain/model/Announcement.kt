package com.cloudbox.app.core.domain.model

/**
 * 一条公告。
 *
 * ⚠️ 蓝云原版并没有"应用内公告中心"：它的"公告"是内嵌 WebView 打开第三方
 * 客服页（兔小巢），且该服务已停运、原版 v1.3.3.0 已移除入口、红点逻辑源码里
 * 也查无实现。所以这里**不照搬**，改为自建：仓库内维护一份 `announcements.json`，
 * App 拉取展示 —— 发公告 = 改仓库文件，无需发版。
 */
data class Announcement(
    /** 稳定唯一 id（红点已读判定依据）；缺失时退化用标题 */
    val id: String,
    val title: String,
    val body: String,
    val date: String? = null,
    val pinned: Boolean = false
)
