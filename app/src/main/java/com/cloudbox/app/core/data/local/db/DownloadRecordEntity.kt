package com.cloudbox.app.core.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 下载记录（Room）。
 *
 * 为什么不用系统 DownloadManager 的查询替代：DownloadManager 的查询 API 在
 * App 进程被杀后仍可查（ContentResolver），但状态字段有限（无暂停原因/文件大小
 * 单位不一），且无法记录"来源分享链接/所属账号"。自建表保存业务上下文，
 * downloadId 关联系统 DownloadManager 任务。
 */
@Entity(tableName = "download_records", indices = [Index("downloadId")])
data class DownloadRecordEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val downloadId: Long,          // 系统 DownloadManager 任务 id
    val fileName: String,
    val url: String,               // 直链
    val referer: String?,          // 下载请求必须带 Referer 否则 403（需求规格 8 节）
    val mimeType: String?,         // APK 时触发安装引导
    val totalBytes: Long = 0,
    val accountUid: String,
    val paused: Boolean = false,   // 用户主动暂停（DownloadManager 本身无持久状态）
    val createdAt: Long = System.currentTimeMillis()
)
