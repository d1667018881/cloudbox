package com.cloudbox.app.core.data.repository

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
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
import java.io.File
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
            records.map { queryStatus(it) }
        }

    override suspend fun enqueue(
        url: String,
        fileName: String,
        referer: String?,
        mimeType: String?,
        accountUid: String
    ): Long = withContext(Dispatchers.IO) {
        val safeName = sanitizeFileName(fileName)
        val request = buildRequest(url, safeName, referer, mimeType)
        val id = downloadManager.enqueue(request)
        db.downloadRecordDao().insert(
            DownloadRecordEntity(
                downloadId = id,
                fileName = safeName,
                url = url,
                referer = referer,
                mimeType = mimeType,
                accountUid = accountUid,
                paused = false
            )
        )
        id
    }

    override suspend fun cancel(downloadId: Long) {
        deleteLocalFile(downloadId)
        downloadManager.remove(downloadId)
        db.downloadRecordDao().deleteByDownloadId(downloadId)
    }

    override suspend fun pause(downloadId: Long) {
        // DownloadManager 无原生 pause API：移除任务但保留记录，并标记 paused。
        // 继续时会重新 enqueue（因 DownloadManager remove 会删临时文件，
        // 故当前实现为"暂停后重新开始"，非断点续传，但满足 UI 控制需求）。
        downloadManager.remove(downloadId)
        db.downloadRecordDao().updatePaused(downloadId, true)
    }

    override suspend fun resume(downloadId: Long) = withContext(Dispatchers.IO) {
        val record = db.downloadRecordDao().getByDownloadId(downloadId) ?: return@withContext
        if (!record.paused) return@withContext
        val request = buildRequest(record.url, record.fileName, record.referer, record.mimeType)
        val newId = downloadManager.enqueue(request)
        // 把旧记录的 downloadId 更新为新任务 id，同时清掉 paused 标记
        db.downloadRecordDao().updateDownloadId(downloadId, newId)
    }

    /** 文件名消毒：移除路径分隔符、控制字符、连续点号，防止路径穿越与 IllegalArgumentException */
    private fun sanitizeFileName(name: String): String {
        if (name.isBlank()) return "download_${System.currentTimeMillis()}"
        var s = name
            .replace(Regex("[\\\\/:*?\"<>|\u0000-\u001F]"), "_")
            .replace(Regex("\\.{2,}"), "_")
            .trim('.', ' ')
        if (s.length > 200) s = s.take(200)
        if (s.isBlank()) s = "download_${System.currentTimeMillis()}"
        return s
    }

    private fun buildRequest(
        url: String,
        fileName: String,
        referer: String?,
        mimeType: String?
    ): DownloadManager.Request {
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
        return request
    }

    /** 查询 DownloadManager 真实状态；下载完成后同步真实文件名（处理同名冲突自动重命名） */
    suspend fun queryStatus(record: DownloadRecordEntity): DownloadTask {
        val query = DownloadManager.Query().setFilterById(record.downloadId)
        return runCatching {
            downloadManager.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        syncRealFileName(record, cursor)
                    }
                    DownloadTask(record.downloadId, record.fileName, record.mimeType, status, total, downloaded, record.referer, record.url, record.paused)
                } else {
                    // 记录还在但 DownloadManager 查不到：已移除（或用户暂停后任务被清理）
                    DownloadTask(record.downloadId, record.fileName, record.mimeType, -1, 0, 0, record.referer, record.url, record.paused)
                }
            }
        }.getOrDefault(
            DownloadTask(record.downloadId, record.fileName, record.mimeType, -1, 0, 0, record.referer, record.url, record.paused)
        )
    }

    private suspend fun syncRealFileName(record: DownloadRecordEntity, cursor: Cursor) {
        val realName = runCatching {
            val idxFile = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
            if (idxFile >= 0) cursor.getString(idxFile)?.takeIf { it.isNotBlank() } else null
        }.getOrNull() ?: runCatching {
            val idxUri = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (idxUri >= 0) cursor.getString(idxUri)?.let { Uri.parse(it).lastPathSegment } else null
        }.getOrNull()
        if (!realName.isNullOrBlank() && realName != record.fileName) {
            db.downloadRecordDao().updateFileName(record.downloadId, realName)
        }
    }

    /** 删除 DownloadManager 已下载的本地文件 */
    private fun deleteLocalFile(downloadId: Long) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        runCatching {
            downloadManager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val idxUri = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val idxFile = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
                when {
                    idxUri >= 0 -> cursor.getString(idxUri)?.let { uriStr ->
                        Uri.parse(uriStr).path?.let { File(it).delete() }
                    }
                    idxFile >= 0 -> cursor.getString(idxFile)?.let { File(it).delete() }
                }
            }
        }
    }
}
