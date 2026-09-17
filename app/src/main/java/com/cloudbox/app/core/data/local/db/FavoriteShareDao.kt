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

    // ==================== V30：检查收藏文件夹更新（对齐 v1.3.4.9） ====================

    /**
     * 写回一次更新检查的结果。
     *
     * [hasUpdate] 与 [lastCheckAt] 必须**同一次事务**写入：原版用
     * `open_link_history[i].update = true` + 列表红点表示「有新内容」，
     * 如果分开写，中途被杀进程会出现「红点亮了但时间戳还是旧的」这种不一致状态，
     * 下次启动的定期检查会重复报同一条。
     */
    @Query("UPDATE favorite_shares SET hasUpdate=:hasUpdate, lastCheckAt=:checkedAt WHERE shareUrl=:url")
    suspend fun updateCheckResult(url: String, hasUpdate: Boolean, checkedAt: Long)

    /** 取出所有文件夹型收藏（只有它们需要做更新检查，原版同此逻辑） */
    @Query("SELECT * FROM favorite_shares WHERE kind='folder'")
    suspend fun getFolders(): List<FavoriteShareEntity>

    /** 修正收藏类型（用户在解析页重新收藏时自动补正） */
    @Query("UPDATE favorite_shares SET kind=:kind, pass=:pass WHERE shareUrl=:url")
    suspend fun updateKindAndPass(url: String, kind: String, pass: String)
}
