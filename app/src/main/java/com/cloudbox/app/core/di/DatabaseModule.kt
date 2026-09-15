package com.cloudbox.app.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cloudbox.app.core.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * v4 → v5：`favorite_shares` 增加 `pinned` 列（收藏置顶）。
 *
 * 为什么这条要单独写显式迁移、不靠 `fallbackToDestructiveMigration`：
 * 收藏夹是**用户自己攒的数据**，重建表等于把收藏全清空，这是不可接受的数据损失。
 * 加一列用 ALTER TABLE 就是无损的，没必要让用户付代价。
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE favorite_shares ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * v5 → v6：`favorite_shares` 增加 kind / pass / hasUpdate / lastCheckAt
 * （对齐原版 v1.3.4.9 的「检查收藏文件夹更新」）。
 *
 * 同样是无损 ALTER TABLE。`kind` 默认 'file' 是**保守选择**：
 * 升级前收藏的数据无法可靠区分文件夹/文件（旧表没存过这个信息），
 * 默认成 'file' 只会导致「旧收藏暂时没有更新检查入口」——比猜成 'folder'
 * 然后对一堆单文件链接跑更新检查（每次都会误报"有更新"）要好得多。
 * 用户重新收藏一次即可自动修正类型。
 */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE favorite_shares ADD COLUMN kind TEXT NOT NULL DEFAULT 'file'")
        db.execSQL("ALTER TABLE favorite_shares ADD COLUMN pass TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE favorite_shares ADD COLUMN hasUpdate INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE favorite_shares ADD COLUMN lastCheckAt INTEGER NOT NULL DEFAULT 0")
    }
}

/** Room 数据库提供者 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "cloudbox.db")
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration() // 其余 schema 变更直接重建；收藏夹已单独保护
            .build()
}
