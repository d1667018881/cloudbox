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
 *
 * 版本历史：
 * - v5：favorite_shares 增加 pinned
 * - v6：favorite_shares 增加 kind / pass / hasUpdate / lastCheckAt
 * - v7：新增 starred_folders（星标文件夹，见 [StarredFolderEntity]）
 */
@Database(
    entities = [
        FileCacheEntity::class,
        SearchIndexEntity::class,
        DownloadRecordEntity::class,
        DirectLinkEntity::class,
        FavoriteShareEntity::class,
        StarredFolderEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileCacheDao(): FileCacheDao
    abstract fun searchIndexDao(): SearchIndexDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun directLinkDao(): DirectLinkDao
    abstract fun favoriteShareDao(): FavoriteShareDao
    abstract fun starredFolderDao(): StarredFolderDao
}
