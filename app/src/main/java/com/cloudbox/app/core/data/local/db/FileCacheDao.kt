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

    /** V50：返回上级缓存优先——取该目录全部已缓存条目（含加载过的所有页） */
    @Query("SELECT * FROM cloud_files WHERE accountUid=:uid AND parentId=:parentId")
    suspend fun getFolder(uid: String, parentId: Long): List<FileCacheEntity>

    /** 清空全部文件列表缓存（「重置应用」用） */
    @Query("DELETE FROM cloud_files")
    suspend fun clearAllCache()
}
