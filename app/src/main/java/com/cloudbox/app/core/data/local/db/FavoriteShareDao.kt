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

    @Query("SELECT * FROM favorite_shares ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FavoriteShareEntity>>

    @Query("DELETE FROM favorite_shares WHERE shareUrl=:url")
    suspend fun delete(url: String)
}
