package com.cloudbox.app.feature.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.domain.repository.UploadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * 批量上传 Worker（WorkManager 后台执行，需求规格 4 节）。
 *
 * 输入参数：
 * - KEY_FOLDER_ID：目标文件夹 id（-1 = 根目录）
 * - KEY_FILE_PATHS：待上传文件路径列表（逗号分隔）
 * - KEY_SPOOF：后缀伪装开关
 *
 * 进度：通过 setProgress 上报，UI 用 WorkManager 的 LiveData/Flow 观察。
 * 为什么用 WorkManager 而不是协程直接传：上传任务应在 App 退到后台/被杀后
 * 继续执行，WorkManager 保证任务完成（系统调度 + 幂等）。
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val uploadRepository: UploadRepository,
    private val settingsStore: SettingsStore
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val folderId = inputData.getLong(KEY_FOLDER_ID, -1L)
        val paths = inputData.getStringArray(KEY_FILE_PATHS)?.toList() ?: emptyList()
        val spoof = inputData.getBoolean(KEY_SPOOF, true)
        if (paths.isEmpty()) return Result.failure()

        val files = paths.map { File(it) }.filter { it.exists() }
        val total = files.size
        files.forEachIndexed { index, file ->
            setProgress(
                androidx.work.Data.Builder()
                    .putInt(KEY_PROGRESS, index)
                    .putInt(KEY_TOTAL, total)
                    .putString(KEY_CURRENT_FILE, file.name)
                    .build()
            )
            uploadRepository.uploadFile(file, folderId, spoof)
        }
        setProgress(
            androidx.work.Data.Builder()
                .putInt(KEY_PROGRESS, total)
                .putInt(KEY_TOTAL, total)
                .build()
        )
        return Result.success()
    }

    companion object {
        const val KEY_FOLDER_ID = "folder_id"
        const val KEY_FILE_PATHS = "file_paths"
        const val KEY_SPOOF = "spoof"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT_FILE = "current_file"
    }
}
