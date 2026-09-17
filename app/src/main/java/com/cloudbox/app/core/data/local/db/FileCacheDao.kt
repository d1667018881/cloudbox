package com.cloudbox.app.core.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FileCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<FileCacheEntity>)

    @Query("DELETE FROM cloud_files WHERE accountUid=:uid AND parentId=:parentId")
    suspend fun clearFolder(uid: String, parentId: Long)

    /** 全盘搜索索引：LIKE 匹配（#25 修复：通配符转义 + ESCAPE） */
    @Query(
        "SELECT * FROM cloud_files WHERE accountUid=:uid AND isFolder=0 AND name LIKE '%'||:keyword||'%' ESCAPE '\\' LIMIT 200"
    )
    suspend fun searchLike(uid: String, keyword: String): List<FileCacheEntity>
}
