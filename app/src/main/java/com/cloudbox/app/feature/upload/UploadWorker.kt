package com.cloudbox.app.feature.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cloudbox.app.core.domain.repository.UploadRepository
import com.cloudbox.app.core.domain.repository.UploadResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.ThreadLocalRandom
import kotlinx.coroutines.delay

/**
 * 批量上传 Worker（WorkManager 后台执行，需求规格 4 节）。
 *
 * 输入参数：
 * - KEY_FOLDER_ID：目标文件夹 id（-1 = 根目录）
 * - KEY_FILE_PATHS：待上传文件路径数组（StringArray，单批 ≤ 50 个，分批在 UploadViewModel）
 * - KEY_SPOOF：后缀伪装开关
 *
 * 审查修复：
 * - 改由 Worker 逐文件调度并实时 setProgress，UI 可见当前文件名与已完成数量。
 * - 大文件自动走 uploadSplit，分卷结果逐条进入失败名单。
 * - 普通文件批量上传循环内 1-3s 随机延时（防 fileup.php 风控，与删除/分卷同款）。
 * - 失败名单通过 outputData 返回；无论批次内是否有失败，一律返回 success，
 *   避免 WorkManager 链式调度把后续批次静默标 FAILED（失败语义由 UI 侧 failedAccumulator 汇总）。
 * - 清理只删本批次上传成功的缓存文件；分卷临时目录兜底清理（uploadSplit 已自清理）。
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
        val filesSucceeded = mutableListOf<Boolean>()
        files.forEachIndexed { index, file ->
            // N1(V3)：普通文件连续上传防风控——除第一个外，每个文件上传前延时 1-3s
            if (index > 0) {
                delay(ThreadLocalRandom.current().nextLong(1_000, 3_001))
            }
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
            filesSucceeded.add(result.success)
            setProgress(
                workDataOf(
                    KEY_PROGRESS to index + 1,
                    KEY_TOTAL to total,
                    KEY_CURRENT_FILE to file.name
                )
            )
        }

        // N3(V3)：只删本次上传成功的缓存文件；失败的文件保留副本，便于用户重试。
        // 缓存布局：uploads/<uuid>/原名（UUID 子目录隔离同名文件）。
        // 分卷临时目录（.cloudbox_split_ 前缀）一律兜底清理（uploadSplit 内部 finally 已自清理）。
        runCatching {
            val uploadsDir = applicationContext.cacheDir.resolve("uploads")
            files.forEachIndexed { index, file ->
                val success = filesSucceeded.getOrNull(index) ?: false
                val inUploadsTree = file.absolutePath.startsWith(uploadsDir.absolutePath + File.separator)
                if (success && inUploadsTree) {
                    file.delete()
                    // 上传成功且子目录已空（如分卷临时文件已自清理）→ 删除 UUID 子目录
                    file.parentFile?.let { dir ->
                        if (dir.listFiles()?.isEmpty() != false) dir.delete()
                    }
                }
            }
            // 兜底：清理残留的分卷临时目录
            uploadsDir.listFiles()?.filter { it.name.startsWith(".cloudbox_split_") }?.forEach { it.deleteRecursively() }
        }

        // N2(V3)：一律返回 success，失败名单只走 outputData。
        // 若返回 failure，WorkManager 链式调度会把后续批次全部标 FAILED 且不执行，
        // 导致"一批失败、后续 70 个文件静默不传"的回归。
        val failed = results.filter { !it.success }
        return Result.success(
            workDataOf(
                KEY_FAILED_FILES to failed.joinToString("\n") { it.fileName },
                KEY_FAILED_MESSAGE to failed.firstOrNull()?.message.orEmpty()
            )
        )
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
