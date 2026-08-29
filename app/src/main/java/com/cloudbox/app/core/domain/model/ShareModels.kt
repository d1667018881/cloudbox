package com.cloudbox.app.core.domain.model

/** 分享信息（获取接口 task=22 文件 / task=18 文件夹） */
data class ShareInfo(
    val shareUrl: String,   // 完整分享链接
    val name: String,
    val pwd: String,        // 提取码（onof=1 时有效）
    val onof: String,       // 是否设提取码（1/0）；onof=0 时 pwd 是无效随机值
    val isFolder: Boolean
)

/** 直链解析结果 */
data class DirectLink(
    val url: String,        // 完整直链
    val fileName: String,
    val referer: String     // 下载时必须携带的 Referer（否则 403）
)

/** 下载记录（UI 层展示用，字段来自系统 DownloadManager 查询） */
data class DownloadTask(
    val downloadId: Long,
    val fileName: String,
    val mimeType: String?,
    val status: Int,        // DownloadManager.STATUS_*；查不到时为 -1
    val bytesTotal: Long,
    val bytesDownloaded: Long,
    val referer: String?,
    val url: String,
    val paused: Boolean = false // 用户主动暂停（DownloadManager 无此状态）
)
