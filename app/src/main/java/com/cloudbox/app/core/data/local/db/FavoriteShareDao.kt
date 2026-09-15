package com.cloudbox.app.core.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteShareDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: FavoriteShareEntity)

    /** 置顶项优先，其次按创建时间倒序 */
    @Query("SELECT * FROM favorite_shares ORDER BY pinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<FavoriteShareEntity>>

    @Query("SELECT * FROM favorite_shares WHERE shareUrl=:url")
    suspend fun get(url: String): FavoriteShareEntity?

    @Query("DELETE FROM favorite_shares WHERE shareUrl=:url")
    suspend fun delete(url: String)

    /** 修改备注 */
    @Query("UPDATE favorite_shares SET remark=:remark WHERE shareUrl=:url")
    suspend fun updateRemark(url: String, remark: String)

    /** 修改名称 */
    @Query("UPDATE favorite_shares SET name=:name WHERE shareUrl=:url")
    suspend fun updateName(url: String, name: String)

    /** 置顶 / 取消置顶 */
    @Query("UPDATE favorite_shares SET pinned=:pinned WHERE shareUrl=:url")
    suspend fun updatePinned(url: String, pinned: Boolean)
}
