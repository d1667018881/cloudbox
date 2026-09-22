package com.cloudbox.app.core.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 星标文件夹 DAO（见 [StarredFolderEntity] 的语义说明） */
@Dao
interface StarredFolderDao {

    /** 订阅某账号的星标列表（sortOrder 升序，同权重按最近星标优先） */
    @Query("SELECT * FROM starred_folders WHERE accountUid=:uid ORDER BY sortOrder ASC, createdAt DESC")
    fun observeAll(uid: String): Flow<List<StarredFolderEntity>>

    @Query("SELECT * FROM starred_folders WHERE accountUid=:uid AND folderId=:folderId")
    suspend fun get(uid: String, folderId: Long): StarredFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: StarredFolderEntity)

    @Query("DELETE FROM starred_folders WHERE accountUid=:uid AND folderId=:folderId")
    suspend fun delete(uid: String, folderId: Long)

    /** 改本地快照名（不动服务端，也不影响源文件夹） */
    @Query("UPDATE starred_folders SET name=:name WHERE accountUid=:uid AND folderId=:folderId")
    suspend fun updateName(uid: String, folderId: Long, name: String)

    @Query("UPDATE starred_folders SET remark=:remark WHERE accountUid=:uid AND folderId=:folderId")
    suspend fun updateRemark(uid: String, folderId: Long, remark: String)

    /** 名称排序结果落盘（原版会保存排序，见 [com.cloudbox.app.core.domain.model.StarredFolder]） */
    @Query("UPDATE starred_folders SET sortOrder=:order WHERE accountUid=:uid AND folderId=:folderId")
    suspend fun updateSortOrder(uid: String, folderId: Long, order: Int)

    /** 退出登录 / 删除账号时清掉该账号的星标（对齐原版「退出登录一并删除星标数据」） */
    @Query("DELETE FROM starred_folders WHERE accountUid=:uid")
    suspend fun clearForAccount(uid: String)

    /** 一次性取全量（排序落盘前重排用，不需要订阅） */
    @Query("SELECT * FROM starred_folders WHERE accountUid=:uid ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getAllOnce(uid: String): List<StarredFolderEntity>
}
