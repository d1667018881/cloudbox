package com.cloudbox.app.core.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SearchIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SearchIndexEntity>)

    @Query("DELETE FROM file_search_fts WHERE accountUid=:uid")
    suspend fun clearForAccount(uid: String)

    /** 索引搜索（#25 修复：LIKE 通配符转义，配合 ESCAPE '\' 使用） */
    @Query(
        "SELECT * FROM file_search_fts WHERE accountUid=:uid AND name LIKE '%'||:query||'%' ESCAPE '\\' LIMIT 200"
    )
    suspend fun searchIndex(uid: String, query: String): List<SearchIndexEntity>

    /** #22/#23 修复：索引行数计数（isSynced 用，替代全表拉取判空） */
    @Query("SELECT COUNT(*) FROM file_search_fts WHERE accountUid=:uid")
    suspend fun countForAccount(uid: String): Int
}
