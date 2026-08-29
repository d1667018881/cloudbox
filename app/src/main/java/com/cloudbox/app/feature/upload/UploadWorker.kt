package com.cloudbox.app.feature.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cloudbox.app.core.domain.repository.UploadRepository
import com.cloudbox.app.core.domain.repository.UploadResult
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
 * 审查修复：
 * - 改由 Worker 逐文件调度并实时 setProgress，UI 可见当前文件名与已完成数量。
 * - 大文件自动走 uploadSplit，分卷结果逐条进入失败名单。
 * - 失败名单通过 outputData 返回，UploadViewModel 多批汇总后展示。
 * - 上传完成后清理本批次缓存文件与分卷临时目录。
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

        setProgress(workDataOf(KEY_PROGRESS to 0, KEY_TOTAL to total, KEY_CURRENT_FILE to ""))

        val results = mutableListOf<UploadResult>()
        files.forEachIndexed { index, file ->
            setProgress(
                workDataOf(
                    KEY_PROGRESS to index,
                    KEY_TOTAL to total,
                    KEY_CURRENT_FILE to file.name
                )
            )
            val result = if (uploadRepository.isOversize(file)) {
                // 超限文件：走分卷上传，分卷结果逐条记录，便于失败重试
                val splitResults = uploadRepository.uploadSplit(file, folderId)
                results.addAll(splitResults)
                UploadResult(
                    file.name, null,
                    splitResults.all { it.success },
                    "分卷 ${splitResults.count { it.success }}/${splitResults.size} 成功"
                )
            } else {
                uploadRepository.uploadFile(file, folderId, spoof)
            }
            results.add(result)
            setProgress(
                workDataOf(
                    KEY_PROGRESS to index + 1,
                    KEY_TOTAL to total,
                    KEY_CURRENT_FILE to file.name
                )
            )
        }

        // 只删本次 paths 涉及的文件（含分卷临时文件前缀），
        // 不删整个 uploads 目录——避免并行批次的缓存文件被误删。
        runCatching {
            val uploadsDir = applicationContext.cacheDir.resolve("uploads")
            paths.forEach { p ->
                val f = File(p)
                if (f.parentFile?.absolutePath == uploadsDir.absolutePath) {
                    f.delete()
                }
            }
            uploadsDir.listFiles()?.filter { it.name.startsWith(".cloudbox_split_") }?.forEach { it.deleteRecursively() }
        }

        val failed = results.filter { !it.success }
        return if (failed.isEmpty()) {
            Result.success()
        } else {
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
