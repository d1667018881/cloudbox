package com.cloudbox.app.core.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 收藏的分享链接（需求规格 6 节：收藏夹，支持备注） */
@Entity(tableName = "favorite_shares")
data class FavoriteShareEntity(
    @PrimaryKey val shareUrl: String,
    val name: String,
    val remark: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
