package com.cloudbox.app.feature.upload

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.domain.repository.UploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** 上传页 UI 状态 */
data class UploadUiState(
    val selectedFiles: List<String> = emptyList(),
    val targetFolderId: Long = -1L,
    val targetFolderName: String = "根目录",
    val uploading: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val currentFile: String = "",
    val message: String? = null,
    val oversizeHint: String? = null,
    val failedFiles: List<String> = emptyList()
)

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadRepository: UploadRepository,
    private val settingsStore: SettingsStore,
    private val workManager: WorkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    private var currentWorkIds: List<UUID> = emptyList()
    private val workStates = mutableMapOf<UUID, WorkInfo.State>()
    private val failedAccumulator = mutableListOf<String>()

    /** 选择文件：SAF 返回 content:// uri，先拷到缓存目录再上传 */
    fun addFiles(uris: List<Uri>) {
        val paths = uris.mapNotNull { copyUriToCache(it)?.absolutePath }
        _uiState.update { it.copy(selectedFiles = it.selectedFiles + paths) }
        checkOversize()
    }

    fun setTargetFolder(folderId: Long, name: String) =
        _uiState.update { it.copy(targetFolderId = folderId, targetFolderName = name) }

    fun removeFile(path: String) {
        _uiState.update { it.copy(selectedFiles = it.selectedFiles - path) }
        // N4(V3)：从列表移除后，若该文件已不在任何选中项，顺带删除其 UUID 子目录（含孤儿缓存）
        if (path !in _uiState.value.selectedFiles) {
            val uploadsRoot = File(context.cacheDir, "uploads")
            val f = File(path)
            if (f.parentFile?.parentFile?.absolutePath == uploadsRoot.absolutePath) {
                f.parentFile?.deleteRecursively()
            }
        }
        checkOversize()
    }

    fun startUpload() {
        val s = _uiState.value
        if (s.selectedFiles.isEmpty() || s.uploading) return
        checkOversize()

        viewModelScope.launch {
            val spoof = settingsStore.suffixSpoofEnabled.first()
            // WorkManager Data 序列化上限 10240 字节（约 100+ 长路径），
            // 全量路径塞进单个 WorkRequest 会抛 IllegalStateException。
            // 按每批 50 个拆分，用 WorkContinuation 链式串联。
            val requests = s.selectedFiles.chunked(50).map { batch ->
                androidx.work.OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(
                        androidx.work.Data.Builder()
                            .putLong(UploadWorker.KEY_FOLDER_ID, s.targetFolderId)
                            .putStringArray(UploadWorker.KEY_FILE_PATHS, batch.toTypedArray())
                            .putBoolean(UploadWorker.KEY_SPOOF, spoof)
                            .build()
                    )
                    .build()
            }
            if (requests.isEmpty()) return@launch
            currentWorkIds = requests.map { it.id }
            workStates.clear()
            failedAccumulator.clear()

            val continuation = requests.drop(1).fold(
                workManager.beginWith(requests.first())
            ) { cont, req -> cont.then(req) }
            continuation.enqueue()

            _uiState.update {
                it.copy(
                    uploading = true,
                    progress = 0,
                    total = s.selectedFiles.size,
                    message = null,
                    failedFiles = emptyList()
                )
            }
            observeWorks(currentWorkIds)
        }
    }

    private fun observeWorks(workIds: List<UUID>) {
        workIds.forEach { workId ->
            viewModelScope.launch {
                workManager.getWorkInfoByIdFlow(workId).collect { info ->
                    if (info.state == WorkInfo.State.RUNNING) {
                        _uiState.update {
                            it.copy(
                                progress = info.progress.getInt(UploadWorker.KEY_PROGRESS, 0),
                                total = info.progress.getInt(UploadWorker.KEY_TOTAL, 0),
                                currentFile = info.progress.getString(UploadWorker.KEY_CURRENT_FILE) ?: ""
                            )
                        }
                    }
                    if (info.state.isFinished) {
                        workStates[workId] = info.state
                        // N2(V3)：Worker 一律返回 success，失败名单始终在 outputData 里。
                        // 任何终态（SUCCEEDED/FAILED/CANCELLED）都读取，不再只认 FAILED。
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
        // N2(V3)：不再用 workStates 的 SUCCEEDED 判定（Worker 现在一律 success），
        // 以 failedAccumulator 是否为空作为"全部成功"的依据。
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
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null, oversizeHint = null) }

    private fun checkOversize() {
        val s = _uiState.value
        if (s.selectedFiles.any { uploadRepository.isOversize(File(it)) }) {
            _uiState.update { it.copy(oversizeHint = "存在超过 100MB 的文件，将自动分卷（95MB/卷）上传") }
        }
    }

    /** content:// → cache 目录真实文件（蓝奏云上传需要真实文件流） */
    private fun copyUriToCache(uri: Uri): File? {
        return runCatching {
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
                }
            }.getOrNull() ?: "upload_${System.currentTimeMillis()}"
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            // N4(V3)：每个文件独立 UUID 子目录（uploads/<uuid>/原名），
            // 防止不同目录选两个同名文件互相覆盖，同时保持文件名不变（云端上传名 = 原名）。
            val dir = File(File(context.cacheDir, "uploads"), UUID.randomUUID().toString().substring(0, 8))
                .apply { mkdirs() }
            val out = File(dir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        }.getOrNull()
    }
}
