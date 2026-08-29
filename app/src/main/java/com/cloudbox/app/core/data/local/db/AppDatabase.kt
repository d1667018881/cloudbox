package com.cloudbox.app.core.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用数据库（Room）。
 *
 * 注意：FTS4 表在 Room 中用 @Fts4 注解更规范，但本项目搜索以 LIKE 兜底为主，
 * FTS 表仅做英文/数字文件名的前缀加速，直接建普通表 + MATCH 查询在 Android
 * 内置 SQLite（默认启用 FTS4）上同样可用，且避免 @Fts4 的表结构限制
 * （FTS 表不能有普通索引、rowid 约束等）。
 */
@Database(
    entities = [
        FileCacheEntity::class,
        SearchIndexEntity::class,
        DownloadRecordEntity::class,
        DirectLinkEntity::class,
        FavoriteShareEntity::class
    ],
    version = 4, // v4：search_index_fts 增加 isFolder 字段（文件夹也入搜索索引）
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileCacheDao(): FileCacheDao
    abstract fun searchIndexDao(): SearchIndexDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun directLinkDao(): DirectLinkDao
    abstract fun favoriteShareDao(): FavoriteShareDao
}
