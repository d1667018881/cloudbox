package com.cloudbox.app.core.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 文件列表缓存（Room）。
 *
 * 为什么需要缓存：蓝奏云管理接口有风控（频繁请求可能被限流），
 * 缓存最近浏览的目录实现秒开 + 离线可看；同时为全盘搜索索引提供数据源。
 *
 * @param accountUid 所属账号（多账号数据隔离）
 * @param parentId   父目录 id（-1 = 根目录）
 * @param isFolder   文件夹还是文件
 * @param rawSize    原始大小字符串（蓝奏云返回 "12.3 MB" 这类带单位文本，保留原样展示）
 * @param cacheTime  缓存时间戳（用于过期判断）
 */
@Entity(tableName = "cloud_files", indices = [Index("parentId"), Index("accountUid"), Index("name")])
data class FileCacheEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val accountUid: String,
    val parentId: Long,
    val id: Long,            // 文件/文件夹 id
    val name: String,
    val isFolder: Boolean,
    val size: String?,
    val time: String?,
    val onof: String?,       // 是否设提取码（1/0）
    val isDes: String?,      // 是否有描述（1/0）
    val cacheTime: Long = System.currentTimeMillis()
)
