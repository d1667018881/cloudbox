package com.cloudbox.app.feature.upload

import android.content.Context
import android.util.Log
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
        // Worker 是否真的跑起来了，是"假成功"排查的第一分水岭：
        // 只要这行没出现在 logcat，就说明任务压根没执行（Hilt 注入失败 /
        // 约束未满足 / 被系统压制），而不是上传本身失败。
        Log.i(TAG, "Worker 启动 id=$id folder=$folderId files=${paths.size} spoof=$spoof")
        if (paths.isEmpty()) {
            Log.w(TAG, "Worker 输入为空，直接判定失败 id=$id")
            return Result.failure()
        }

        val files = paths.map { File(it) }.filter { it.exists() }
        // V5 修复：缓存文件丢失（系统清理 cacheDir）时，旧逻辑会静默跳过丢失文件
        // ——全丢时报"0 个全部成功"（假成功），部分丢时丢失的文件无声消失。
        // 改为：丢失文件全部计入失败名单，如实上报（按路径精确比对，同名文件不误判）。
        val existingPaths = files.map { it.absolutePath }.toSet()
        val missingNames = paths.filter { it !in existingPaths }.map { File(it).name }
        if (files.isEmpty()) {
            return Result.success(
                workDataOf(
                    KEY_FAILED_FILES to missingNames.joinToString("\n"),
                    KEY_FAILED_MESSAGE to "本地缓存文件已丢失，请重新选择后上传"
                )
            )
        }
        val total = files.size

        setProgress(workDataOf(KEY_PROGRESS to 0, KEY_TOTAL to total, KEY_CURRENT_FILE to ""))

        val results = mutableListOf<UploadResult>()
        val filesSucceeded = mutableListOf<Boolean>()
        try {
          files.forEachIndexed { index, file ->
              // N1(V3)：连续上传防风控——每个文件上传前都延时（含第一个）。
              //
              // 旧值是 1-3s 且只在 index>0 时延时；现在 UploadViewModel 改成
              // 「一个文件一个 Worker」后 index 恒为 0，等于完全没延时了，
              // 所以改为每文件都延；同时把区间压到 300-800ms——原版 Lua 里
              // 压根没有上传延时，1-3s 是我们自己加的，多文件时会把总时长
              // 推向 WorkManager 的 10 分钟上限，得不偿失。
              delay(ThreadLocalRandom.current().nextLong(300, 801))
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
        } catch (t: Throwable) {
            // 任务级异常兜底：绝不让 doWork 把异常抛出去。
            // 抛出去 = Result.failure() = FAILED，而 FAILED 的 outputData 是空的，
            // UI 侧只能报"被中断"，用户和我们都拿不到真正原因。
            // 这里把没结果的文件补成失败并带上异常信息，仍返回 success 交给名单上报。
            // CancellationException 必须原样抛出——协程取消不是错误，吞掉会破坏结构化并发。
            if (t is kotlinx.coroutines.CancellationException) throw t
            files.forEachIndexed { index, file ->
                if (filesSucceeded.getOrNull(index) == null) {
                    results.add(
                        UploadResult(
                            file.name, null, false,
                            "任务异常：${t.javaClass.simpleName} ${t.message}"
                        )
                    )
                    filesSucceeded.add(false)
                }
            }
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
                    // ⚠️ 去掉"目录空了就删目录"（2026-09-12）。
                    //
                    //    原写法 `if (dir.listFiles()?.isEmpty() != false) dir.delete()`
                    //    有两个问题：
                    //    1. `listFiles()` 返回 null（目录不存在/无权限）时，`null?.isEmpty()`
                    //       是 null，`!= false` 判为 true —— 会把一个**都没读到的**目录当成空目录删掉；
                    //    2. 多文件上传时同一 uploads/<uuid>/ 目录由多个 Worker 依次处理，
                    //       只要某个时刻目录恰好为空（例如同批文件已被上一轮清走），
                    //       就会把后面待传文件所在目录一并删除，后继 Worker 只能报
                    //       "本地缓存文件已丢失"。
                    //    残留的空 UUID 目录无害（系统会回收 cacheDir），不值得为它冒险。
                    //    统一由 uploadsDir 的整体清理负责（见下方兜底）。
                }
            }
            // 兜底：清理残留的分卷临时目录
            uploadsDir.listFiles()?.filter { it.name.startsWith(".cloudbox_split_") }?.forEach { it.deleteRecursively() }
        }

        // N2(V3)：一律返回 success，失败名单只走 outputData。
        // 若返回 failure，WorkManager 链式调度会把后续批次全部标 FAILED 且不执行，
        // 导致"一批失败、后续 70 个文件静默不传"的回归。
        val failed = results.filter { !it.success }
        // V5：部分丢失的文件并入失败名单（否则无声消失）
        val failedNames = (failed.map { it.fileName } + missingNames).distinct()
        Log.i(TAG, "Worker 结束 id=$id 结果 ${results.size} 条，失败 ${failedNames.size} 个" +
                (if (failed.isNotEmpty()) "：${failed.first().message}" else ""))
        return Result.success(
            workDataOf(
                KEY_FAILED_FILES to failedNames.joinToString("\n"),
                KEY_FAILED_MESSAGE to (failed.firstOrNull()?.message
                    ?: if (missingNames.isNotEmpty()) "部分本地缓存文件已丢失" else "").orEmpty()
            )
        )
    }

    companion object {
        /** logcat 过滤：adb logcat -s CloudBoxUpload */
        private const val TAG = "CloudBoxUpload"

        /** 查询 tag（固定值）：App 重启后 UploadViewModel 凭此找出全部上传批次（见其 init） */
        const val TAG_UPLOAD_SESSION = "cloudbox_upload_session"

        /** 会话分组 tag 前缀：每次 enqueue 一个新 uuid，防止多会话批次互相混淆
         *  （S5 修复：旧实现全共享一个 tag，传两批 + 杀进程后恢复会把已完成
         *  旧会话的文件数错算进新会话的进度基数） */
        const val TAG_SESSION_PREFIX = "cloudbox_upload_session:"

        /** 批大小 tag 前缀：WorkInfo 不暴露 inputData，恢复时批大小从 tag 解析
         *  （形如 "cloudbox_upload_size:<uuid>:<n>"，见 UploadViewModel.enqueueUpload） */
        const val TAG_SIZE_PREFIX = "cloudbox_upload_size:"
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
