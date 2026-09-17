package com.cloudbox.app.core.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DirectLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: DirectLinkEntity)

    @Query("SELECT * FROM direct_link_cache WHERE shareUrl=:shareUrl AND resolvedAt > :after")
    suspend fun getFresh(shareUrl: String, after: Long): DirectLinkEntity?

    /** 清空全部直链缓存（「重置应用」用） */
    @Query("DELETE FROM direct_link_cache")
    suspend fun clearAll()
}
