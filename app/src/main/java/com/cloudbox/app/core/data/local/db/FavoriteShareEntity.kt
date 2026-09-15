package com.cloudbox.app.core.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 收藏的分享链接（需求规格 6 节：收藏夹，支持备注 + 置顶） */
@Entity(tableName = "favorite_shares")
data class FavoriteShareEntity(
    @PrimaryKey val shareUrl: String,
    val name: String,
    val remark: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 是否置顶。置顶项永远排在列表最前，其余按 [createdAt] 倒序。
     *
     * 用 Boolean 而不是 sortOrder 整数：原版收藏夹只有"置顶/取消置顶"两态，
     * 没有手工拖拽排序，多一个整数只会增加维护成本。
     */
    val pinned: Boolean = false
)
