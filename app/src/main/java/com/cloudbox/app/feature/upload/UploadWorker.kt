package com.cloudbox.app.feature.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cloudbox.app.core.domain.repository.UploadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * 批量上传 Worker（WorkManager 后台执行，需求规格 4 节）。
 *
 * 输入参数：
 * - KEY_FOLDER_ID：目标文件夹 id（-1 = 根目录）
 * - KEY_FILE_PATHS：待上传文件路径列表（逗号分隔，单批 ≤ 50 个，见 UploadViewModel 分批）
 * - KEY_SPOOF：后缀伪装开关
 *
 * 审查修复（CODE_REVIEW #3）：
 * - 改调 uploadBatch()：其内部已实现 超限文件自动分卷（95MB/卷）+ 批量 1-3s 随机延时防封
 *   + 失败计数；旧实现逐文件调 uploadFile()，超限必被服务端拒绝且无延时
 * - 失败检测：存在失败文件时返回 Result.failure()，失败名单写入 outputData（UI 可提示）
 * - 上传完成后清理缓存目录（#30 配套）
 *
 * 进度：setProgress 上报（uploadBatch 为整批执行，进度粒度=批次完成/进行中，UI 显示总量）
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val uploadRepository: UploadRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val folderId = inputData.getLong(KEY_FOLDER_ID, -1L)
        val paths = inputData.getStringArray(KEY_FILE_PATHS)?.toList() ?: emptyList()
        val spoof = inputData.getBoolean(KEY_SPOOF, true)
        if (paths.isEmpty()) return Result.failure()

        val files = paths.map { File(it) }.filter { it.exists() }
        val total = files.size

        setProgress(
            Data.Builder()
                .putInt(KEY_PROGRESS, 0)
                .putInt(KEY_TOTAL, total)
                .build()
        )

        // #3 修复：uploadBatch 内部处理 分卷/延时/失败计数（见 UploadRepositoryImpl）
        val results = uploadRepository.uploadBatch(files, folderId, spoof)
        val failed = results.filter { !it.success }

        setProgress(
            Data.Builder()
                .putInt(KEY_PROGRESS, total)
                .putInt(KEY_TOTAL, total)
                .build()
        )

        // 清理上传缓存（SAF 拷贝到 cache/uploads 的临时文件，#30 配套）
        runCatching {
            applicationContext.cacheDir.resolve("uploads").deleteRecursively()
        }

        return if (failed.isEmpty()) {
            Result.success()
        } else {
            // 有失败：返回 failure + 失败文件名单（UI 可展示重试）
            Result.failure(
                workDataOf(
                    KEY_FAILED_FILES to failed.joinToString("\n") { it.fileName },
                    KEY_FAILED_MESSAGE to failed.firstOrNull()?.message.orEmpty()
                )
            )
        }
    }

    companion object {
        const val KEY_FOLDER_ID = "folder_id"
        const val KEY_FILE_PATHS = "file_paths"
        const val KEY_SPOOF = "spoof"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_FAILED_FILES = "failed_files"
        const val KEY_FAILED_MESSAGE = "failed_message"
    }
}
