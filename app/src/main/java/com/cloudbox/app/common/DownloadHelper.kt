package com.cloudbox.app.common

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * 下载完成后的处理辅助。
 *
 * APK 安装流程（需求规格 8 节）：
 * MIME 为 application/vnd.android.package-archive 时：
 * 1. 校验 REQUEST_INSTALL_PACKAGES 权限（Android 8+ 需引导用户在系统设置开启"安装未知应用"）
 * 2. 用 FileProvider 提供 content:// uri 跳转安装器
 */
object DownloadHelper {

    /** 从 DownloadManager 查询下载完成后的本地文件 uri */
    fun getCompletedFileUri(context: Context, downloadId: Long): Uri? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        return runCatching {
            dm.query(query).use { cursor: Cursor? ->
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status != DownloadManager.STATUS_SUCCESSFUL) return null
                    val uriStr = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    Uri.parse(uriStr)
                } else null
            }
        }.getOrNull()
    }

    /** 打开已下载文件（APK 走安装器，其他走系统打开） */
    fun openFile(context: Context, uri: Uri?, mimeType: String?) {
        if (uri == null) return
        if (mimeType == "application/vnd.android.package-archive") {
            openApk(context, uri)
        } else {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }.onFailure {
                Toast.makeText(context, "无法打开文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 安装磁盘上的 APK 文件（自更新用）。
     *
     * 走自建 FileProvider（authority = `<包名>.fileprovider`，见 AndroidManifest 与
     * res/xml/file_paths.xml）换出 content:// URI —— 直接把 `file://` 交给安装器
     * 在 Android 7+ 会抛 FileUriExposedException。自更新包下载到 cacheDir/updates/，
     * 该路径已在 file_paths.xml 中声明。
     */
    fun installApkFromFile(context: Context, file: java.io.File) {
        if (!file.exists()) {
            Toast.makeText(context, "安装包不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, "无法准备安装包", Toast.LENGTH_SHORT).show()
            return
        }
        openApk(context, uri)
    }

    fun openApk(context: Context, uri: Uri) {
        // Android 8+：安装未知应用需要 REQUEST_INSTALL_PACKAGES + 用户授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(context, "需要开启「安装未知应用」权限", Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                )
            }
            return
        }
        try {
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(install)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "未找到安装器", Toast.LENGTH_SHORT).show()
        }
    }
}
