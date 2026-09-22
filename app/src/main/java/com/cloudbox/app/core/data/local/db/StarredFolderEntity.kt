package com.cloudbox.app.core.data.local.db

import androidx.room.Entity

/**
 * 星标文件夹表（对齐原版「星标文件夹」）。
 *
 * 为什么主键是 **(accountUid, folderId) 复合**而不是单独的 folderId：
 * folderId 是服务端下发的数字 id，跨账号并不保证语义唯一；而原版明确要求
 * 「不同账号的星标文件夹信息独立储存」，用账号维度限定的复合主键才既正确
 * 又能顺带支撑「退出登录清掉本账号星标」这条按 uid 的整批删除。
 *
 * ⚠️ 新增此表时数据库版本 6 → 7，必须配显式迁移（见 DatabaseModule）：
 * `fallbackToDestructiveMigration` 在没有匹配迁移时会**整库重建**，
 * 那会顺手清掉用户的收藏夹与下载记录 —— 星标是用户自己攒的数据，不能这样丢。
 */
@Entity(tableName = "starred_folders", primaryKeys = ["accountUid", "folderId"])
data class StarredFolderEntity(
    val accountUid: String,
    val folderId: Long,
    /** 星标当时的文件夹名（快照；原版星标后信息不自动更新） */
    val name: String,
    /** 用户在星标页可自定义的备注（可空） */
    val remark: String = "",
    /** 排序权重：越小越靠前；相同则按 createdAt 倒序 */
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
