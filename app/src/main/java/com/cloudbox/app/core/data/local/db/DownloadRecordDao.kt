package com.cloudbox.app.core.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DownloadRecordEntity)

    @Query("SELECT * FROM download_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadRecordEntity>>

    @Query("DELETE FROM download_records WHERE downloadId=:downloadId")
    suspend fun deleteByDownloadId(downloadId: Long)

    @Query("DELETE FROM download_records WHERE rowId=:rowId")
    suspend fun deleteByRowId(rowId: Long)

    @Query("SELECT * FROM download_records WHERE downloadId=:downloadId")
    suspend fun getByDownloadId(downloadId: Long): DownloadRecordEntity?
}
