package com.cloudbox.app.core.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 收藏的分享链接（需求规格 6 节：收藏夹，支持备注 + 置顶）。
 *
 * V30 对齐原版 v1.3.4.9：新增 [kind] / [pass] / [hasUpdate] / [lastCheckAt]，
 * 支撑「检查收藏文件夹更新」。新增字段均带默认值，Room 迁移走
 * [AppDatabase] 的 MIGRATION_*（见该文件）。
 */
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
    val pinned: Boolean = false,

    /** 收藏类型："folder" / "file"（原版 open_link_history[i].type） */
    val kind: String = "file",

    /** 提取码（原版 open_link_history[i].pass） */
    val pass: String = "",

    /** 是否检测到更新 → 列表红点（原版 open_link_history[i].update） */
    val hasUpdate: Boolean = false,

    /** 上次检查时间戳（毫秒），0 = 从未检查 */
    val lastCheckAt: Long = 0L
)
