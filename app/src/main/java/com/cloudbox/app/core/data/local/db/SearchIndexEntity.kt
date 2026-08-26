package com.cloudbox.app.core.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 搜索索引表（FTS4）。
 *
 * 中文分词局限说明：SQLite FTS4 默认 simple tokenizer 按空格/标点分词，
 * 中文文件名整串作为一个 token，无法做词内匹配。因此本项目搜索策略是双轨：
 * - 英文/数字文件名 → FTS4 前缀查询（快）
 * - 中文 → LIKE '%kw%' 兜底（FileCacheDao.searchLike）
 * 两者结果合并去重后展示。这是 Room FTS 在中文场景下的通用妥协方案。
 */
@Entity(tableName = "file_search_fts")
data class SearchIndexEntity(
    @PrimaryKey val fileId: Long,
    val accountUid: String,
    val name: String,
    val parentId: Long,
    val size: String?,
    val time: String?
)
