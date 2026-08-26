package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.DownloadTask
import kotlinx.coroutines.flow.Flow

/** 下载仓库：系统 DownloadManager 封装 + 下载记录（Room） */
interface DownloadRepository {

    /** 观察全部下载记录（含实时状态） */
    fun observeRecords(): Flow<List<DownloadTask>>

    /** 入队下载；返回 downloadId */
    suspend fun enqueue(url: String, fileName: String, referer: String?, mimeType: String?, accountUid: String): Long

    /** 取消/删除下载与记录 */
    suspend fun cancel(downloadId: Long)
}
