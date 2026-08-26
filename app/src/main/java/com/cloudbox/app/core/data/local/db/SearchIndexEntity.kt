package com.cloudbox.app.core.data.local.db

import androidx.room.Entity

/**
 * 搜索索引表。
 *
 * 说明（审查 CODE_REVIEW #18/#32 修正）：早期文档自称 FTS4，实际是普通表 + LIKE 查询
 * （Room @Fts4 虚拟表对中文分词无效且表结构受限），统一说法避免后续维护者误用 MATCH。
 * 主键为 (accountUid, fileId) 复合主键（文件 id 全局唯一，但多账号下可能重复）。
 *
 * 中文分词局限：LIKE '%kw%' 对中文/英文/数字统一可用，
 * 个人网盘规模（千级文件）性能足够；数据量达到万级再引入 FTS 前缀加速。
 */
@Entity(tableName = "file_search_fts", primaryKeys = ["accountUid", "fileId"])
data class SearchIndexEntity(
    val accountUid: String,
    val fileId: Long,
    val name: String,
    val parentId: Long,
    val size: String?,
    val time: String?
)
