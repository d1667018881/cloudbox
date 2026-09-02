package com.cloudbox.app.feature.upload

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cloudbox.app.core.domain.repository.UploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * 上传调度 ViewModel（V5 重构：上传并入网盘页 FAB，不再是独立 Tab）。
 *
 * 使用方式：FileListScreen 的 + FAB → SAF 多选 → [enqueueUpload]（目标 =
 * 当前文件夹）→ 底部进度横幅 → [uploadFinished] 事件触发列表刷新。
 *
 * 进度语义（多批合并）：WorkManager 按每批 ≤50 文件链式串联（Data 10KB 上限），
 * 全局进度 = 已完成批次的文件数累计 + 当前批次的批内进度，total = 全部文件数
 * （修复 V3 P3 的"total 随批次跳变"问题）。
 *
 * 失败语义（V3 N2）：Worker 一律返回 success，失败名单走 outputData，
 * 这里汇总 failedAccumulator 判定"全部成功/部分失败"。
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadRepository: UploadRepository,
    private val workManager: WorkManager
) : ViewModel() {

    /** 上传进度横幅状态 */
    data class UploadUiState(
        val uploading: Boolean = false,
        val progress: Int = 0,
        val total: Int = 0,
        val currentFile: String = "",
        val message: String? = null,
        val failedFiles: List<String> = emptyList()
    )

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    /** 一次上传会话结束（不论成败）发射一次；网盘页收集后刷新当前文件夹列表 */
    private val _uploadFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val uploadFinished: SharedFlow<Unit> = _uploadFinished.asSharedFlow()

    private var currentWorkIds: List<UUID> = emptyList()
    private val workStates = mutableMapOf<UUID, WorkInfo.State>()
    private val failedAccumulator = mutableListOf<String>()
    private var globalTotal = 0

    init {
        // V5 自查修复：进程在上传中被杀后重进 App，ViewModel 重建会丢失对在途
        // Worker 的观察——后台传完后 uploadFinished 无人发射，列表又不刷新了
        // （正是 V5 主修缺陷的残留路径）。凭 tag 重新接管在途会话。
        viewModelScope.launch {
            val infos = runCatching { workInfosByTag(UploadWorker.TAG_UPLOAD_SESSION) }
                .getOrDefault(emptyList())
            val active = infos.filter { !it.state.isFinished }
            if (active.isEmpty()) return@launch

            fun batchSizeOf(info: WorkInfo): Int =
                info.progress.getInt(UploadWorker.KEY_TOTAL, 0).takeIf { it > 0 }
                    ?: info.inputData.getStringArray(UploadWorker.KEY_FILE_PATHS)?.size ?: 0

            currentWorkIds = active.map { it.id }
            workStates.clear()
            failedAccumulator.clear()
            // 已完成批（SUCCEEDED）的失败名单与文件数一并并入，进度从正确基数续算
            val finishedInfos = infos.filter { it.state == WorkInfo.State.SUCCEEDED }
            finishedInfos.forEach { info ->
                info.outputData.getString(UploadWorker.KEY_FAILED_FILES)
                    ?.split("\n")?.filter { it.isNotBlank() }
                    ?.let { failedAccumulator.addAll(it) }
            }
            val initialFinished = finishedInfos.sumOf { batchSizeOf(it) }
            globalTotal = infos.filter { it.state != WorkInfo.State.CANCELLED }.sumOf { batchSizeOf(it) }

            _uiState.update {
                it.copy(uploading = true, progress = initialFinished, total = globalTotal, message = null)
            }
            observeWorks(currentWorkIds, active.map { batchSizeOf(it) }, initialFinished)
        }
    }

    /**
     * WorkManager.getWorkInfosByTag 返回 ListenableFuture（KTX 无 tag 版 Flow 扩展，
     * 只有 LiveData 版），用 suspendCancellableCoroutine 手动桥接，避免引 guava 协程依赖。
     */
    private suspend fun workInfosByTag(tag: String): List<WorkInfo> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val future = workManager.getWorkInfosByTag(tag)
            future.addListener({
                cont.resumeWith(kotlin.runCatching { future.get() })
            }, context.mainExecutor)
        }

    /** SAF 多选入口：拷贝到缓存 → 分批 → 链式入队（folderId = 当前目录，-1 = 根） */
    fun enqueueUpload(uris: List<Uri>, folderId: Long, spoof: Boolean = true) {
        if (uris.isEmpty()) return
        if (_uiState.value.uploading) {
            _uiState.update { it.copy(message = "已有上传任务进行中，请稍候") }
            return
        }
        // 防重入（V5 自查修复）：先占位 uploading=true。拷贝期间（大文件可达数秒）
        // 用户再次点上传会双会话并行，currentWorkIds 互相覆盖、进度混乱。
        _uiState.update { it.copy(uploading = true, message = null) }
        viewModelScope.launch {
            // 大文件拷贝必须在 IO 线程（默认 viewModelScope = Main）
            val paths = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.mapNotNull { copyUriToCache(it)?.absolutePath }
            }
            if (paths.isEmpty()) {
                _uiState.update { it.copy(uploading = false, message = "所选文件读取失败") }
                return@launch
            }

            // WorkManager Data 序列化上限 10240 字节 → 按每批 50 个拆分链式串联
            val batches = paths.chunked(50)
            val requests = batches.map { batch ->
                androidx.work.OneTimeWorkRequestBuilder<UploadWorker>()
                    .addTag(UploadWorker.TAG_UPLOAD_SESSION)
                    .setInputData(
                        androidx.work.Data.Builder()
                            .putLong(UploadWorker.KEY_FOLDER_ID, folderId)
                            .putStringArray(UploadWorker.KEY_FILE_PATHS, batch.toTypedArray())
                            .putBoolean(UploadWorker.KEY_SPOOF, spoof)
                            .build()
                    )
                    .build()
            }
            currentWorkIds = requests.map { it.id }
            workStates.clear()
            failedAccumulator.clear()
            globalTotal = paths.size

            val continuation = requests.drop(1).fold(
                workManager.beginWith(requests.first())
            ) { cont, req -> cont.then(req) }
            continuation.enqueue()

            _uiState.update {
                it.copy(
                    uploading = true,
                    progress = 0,
                    total = globalTotal,
                    message = null,
                    failedFiles = emptyList(),
                    currentFile = ""
                )
            }
            observeWorks(currentWorkIds, batches.map { it.size })
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    /** 观察各批次：RUNNING 更新全局进度，终态累计完成数并收集失败名单 */
    private fun observeWorks(
        workIds: List<UUID>,
        batchSizes: List<Int>,
        initialFinished: Int = 0
    ) {
        var finishedCount = initialFinished // 已完成批次累计的文件数（批间串行，无并发写）
        workIds.forEachIndexed { idx, workId ->
            viewModelScope.launch {
                workManager.getWorkInfoByIdFlow(workId).collect { info ->
                    if (info.state == WorkInfo.State.RUNNING) {
                        val p = info.progress.getInt(UploadWorker.KEY_PROGRESS, 0)
                        _uiState.update {
                            it.copy(
                                progress = finishedCount + p,
                                total = globalTotal,
                                currentFile = info.progress.getString(UploadWorker.KEY_CURRENT_FILE) ?: ""
                            )
                        }
                    }
                    if (info.state.isFinished && workStates[workId] == null) {
                        workStates[workId] = info.state
                        finishedCount += batchSizes.getOrElse(idx) { 0 }
                        // N2(V3)：任何终态都读 outputData 失败名单（Worker 一律 success）
                        info.outputData.getString(UploadWorker.KEY_FAILED_FILES)
                            ?.split("\n")
                            ?.filter { it.isNotBlank() }
                            ?.let { failedAccumulator.addAll(it) }
                        checkAllFinished()
                    }
                }
            }
        }
    }

    private fun checkAllFinished() {
        if (currentWorkIds.any { it !in workStates }) return
        val allSuccess = failedAccumulator.isEmpty()
        _uiState.update {
            it.copy(
                uploading = false,
                message = if (allSuccess) "上传完成" else "上传完成，部分文件失败",
                failedFiles = failedAccumulator.distinct(),
                progress = 0,
                currentFile = ""
            )
        }
        _uploadFinished.tryEmit(Unit)
    }

    /** SAF content:// 拷入缓存 uploads/<uuid>/原名（UUID 子目录隔离同名文件，V3 N4） */
    private fun copyUriToCache(uri: Uri): File? {
        return runCatching {
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
                }
            }.getOrNull() ?: "upload_${System.currentTimeMillis()}"
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dir = File(context.cacheDir, "uploads/${UUID.randomUUID()}").apply { mkdirs() }
            val out = File(dir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        }.getOrNull()
    }
}
