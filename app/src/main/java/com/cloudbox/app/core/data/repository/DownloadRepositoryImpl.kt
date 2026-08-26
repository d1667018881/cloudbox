package com.cloudbox.app.core.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.core.data.local.db.AppDatabase
import com.cloudbox.app.core.data.local.db.DownloadRecordEntity
import com.cloudbox.app.core.domain.model.DownloadTask
import com.cloudbox.app.core.domain.repository.DownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载仓库：系统 DownloadManager 接管（需求规格 8 节，本期方案）。
 *
 * 为什么必须带桌面 UA 和 Referer：直链绑定 Referer（解析时的分享域），
 * 不带 Referer 或带手机 UA 会 403（蓝奏云按 UA 隐藏 APK 等格式入口）。
 *
 * 注意：DownloadManager 的请求头需要在 enqueue 时通过
 * addRequestHeader("User-Agent", ...) 显式设置——它不走 OkHttp 拦截器。
 */
@Singleton
class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase
) : DownloadRepository {

    private val downloadManager: DownloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override fun observeRecords(): Flow<List<DownloadTask>> =
        db.downloadRecordDao().observeAll().map { records ->
            records.map { record -> queryStatus(record) }
        }

    override suspend fun enqueue(
        url: String,
        fileName: String,
        referer: String?,
        mimeType: String?,
        accountUid: String
    ): Long = withContext(Dispatchers.IO) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("来自云匣")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
            // 桌面 UA + Referer：否则 403（需求规格 8 节）
            .addRequestHeader("User-Agent", AppConstants.DESKTOP_UA)
        referer?.let { request.addRequestHeader("Referer", it) }
        mimeType?.let { request.setMimeType(it) }

        val id = downloadManager.enqueue(request)
        db.downloadRecordDao().insert(
            DownloadRecordEntity(
                downloadId = id,
                fileName = fileName,
                url = url,
                referer = referer,
                mimeType = mimeType,
                accountUid = accountUid
            )
        )
        id
    }

    override suspend fun cancel(downloadId: Long) {
        downloadManager.remove(downloadId)
        db.downloadRecordDao().deleteByDownloadId(downloadId)
    }

    fun queryStatus(record: DownloadRecordEntity): DownloadTask {
        val query = DownloadManager.Query().setFilterById(record.downloadId)
        return runCatching {
            downloadManager.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    DownloadTask(record.downloadId, record.fileName, record.mimeType, status, total, downloaded, record.referer, record.url)
                } else {
                    // 记录还在但 DownloadManager 查不到：已移除
                    DownloadTask(record.downloadId, record.fileName, record.mimeType, -1, 0, 0, record.referer, record.url)
                }
            }
        }.getOrDefault(
            DownloadTask(record.downloadId, record.fileName, record.mimeType, -1, 0, 0, record.referer, record.url)
        )
    }
}
