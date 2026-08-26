package com.cloudbox.app.core.di

import android.content.Context
import androidx.room.Room
import com.cloudbox.app.core.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Room 数据库提供者 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "cloudbox.db")
            .fallbackToDestructiveMigration() // 自用项目：schema 变更直接重建，避免升级崩溃
            .build()
}
