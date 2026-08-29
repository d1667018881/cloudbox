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

    @Query("UPDATE download_records SET paused=:paused WHERE downloadId=:downloadId")
    suspend fun updatePaused(downloadId: Long, paused: Boolean)

    @Query("UPDATE download_records SET downloadId=:newId, paused=0 WHERE downloadId=:oldId")
    suspend fun updateDownloadId(oldId: Long, newId: Long)

    @Query("UPDATE download_records SET fileName=:fileName WHERE downloadId=:downloadId")
    suspend fun updateFileName(downloadId: Long, fileName: String)
}
