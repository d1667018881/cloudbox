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

    /**
     * 索引搜索。
     * 说明：Room @Fts4 虚拟表对中文分词无效且表结构受限（无主键/普通索引），
     * 因此本表用普通表 + LIKE 查询——对个人网盘规模（千级文件）性能足够，
     * 且中文/英文/数字统一可用。FTS 前缀加速在数据量达到万级后再引入。
     */
    @Query("SELECT * FROM file_search_fts WHERE accountUid=:uid AND name LIKE '%'||:query||'%' LIMIT 200")
    suspend fun searchIndex(uid: String, query: String): List<SearchIndexEntity>
}
