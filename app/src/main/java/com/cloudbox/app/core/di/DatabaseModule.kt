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

/** Room 数据库提供者 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "cloudbox.db")
            .addMigrations(MIGRATION_4_5)
            .fallbackToDestructiveMigration() // 其余 schema 变更直接重建；收藏夹已单独保护
            .build()
}
