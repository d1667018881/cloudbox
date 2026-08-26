package com.cloudbox.app.core.domain.model

/** 收藏的分享链接（UI 模型） */
data class FavoriteShare(
    val shareUrl: String,
    val name: String,
    val remark: String = "",
    val createdAt: Long = 0L
)
