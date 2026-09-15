package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.DownloadTask
import kotlinx.coroutines.flow.Flow

/** 下载仓库：系统 DownloadManager 封装 + 下载记录（Room） */
interface DownloadRepository {

    /** 观察全部下载记录（含实时状态） */
    fun observeRecords(): Flow<List<DownloadTask>>

    /** 入队下载；返回 downloadId */
    suspend fun enqueue(url: String, fileName: String, referer: String?, mimeType: String?, accountUid: String): Long

    /** 取消/删除下载、本地文件与记录 */
    suspend fun cancel(downloadId: Long)

    /** 暂停下载（记录保留，用户可继续） */
    suspend fun pause(downloadId: Long)

    /** 继续已暂停的下载（重新入队，新 downloadId） */
    suspend fun resume(downloadId: Long)

    /**
     * 清空全部下载记录（含 DownloadManager 任务与已下载的本地文件）。
     *
     * 对齐原版 `download.lua` 的「清空列表」：原版是**只清记录不删已下载文件**，
     * 因为用户可能还想留着文件。这里保持一致——只移除系统任务与数据库记录。
     */
    suspend fun clearAll()
}
