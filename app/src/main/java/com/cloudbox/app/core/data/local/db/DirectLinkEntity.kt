package com.cloudbox.app.core.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 直链解析缓存（Room）。
 *
 * 为什么缓存：直链有效期约 2 小时且绑定 Referer（需求规格 7 节），
 * 解析一次后 1 小时内（TTL）直接复用，避免频繁访问分享页触发风控
 * （同 UA/IP 对同一分享页 7 天访问上限约 5 次，超限被临时拉黑）。
 */
@Entity(tableName = "direct_link_cache")
data class DirectLinkEntity(
    @PrimaryKey val shareUrl: String,   // 分享链接（含密码时记录带密码形式）
    val directUrl: String,
    val fileName: String,
    val referer: String,
    val resolvedAt: Long = System.currentTimeMillis()
)
