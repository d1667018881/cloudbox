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
    val oversizeHint: String? = null
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
        checkOversize()
    }

    fun startUpload() {
        val s = _uiState.value
        if (s.selectedFiles.isEmpty() || s.uploading) return
        checkOversize()

        viewModelScope.launch {
            val spoof = settingsStore.suffixSpoofEnabled.first()
            val request = androidx.work.OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    androidx.work.Data.Builder()
                        .putLong(UploadWorker.KEY_FOLDER_ID, s.targetFolderId)
                        .putStringArray(UploadWorker.KEY_FILE_PATHS, s.selectedFiles.toTypedArray())
                        .putBoolean(UploadWorker.KEY_SPOOF, spoof)
                        .build()
                )
                .build()
            workManager.enqueue(request)
            _uiState.update { it.copy(uploading = true, progress = 0, total = s.selectedFiles.size) }
            observeWork(request.id)
        }
    }

    private fun observeWork(workId: UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { info ->
                _uiState.update {
                    it.copy(
                        progress = info.progress.getInt(UploadWorker.KEY_PROGRESS, 0),
                        total = info.progress.getInt(UploadWorker.KEY_TOTAL, 0),
                        currentFile = info.progress.getString(UploadWorker.KEY_CURRENT_FILE) ?: ""
                    )
                }
                if (info.state.isFinished) {
                    val ok = info.state == WorkInfo.State.SUCCEEDED
                    _uiState.update {
                        it.copy(
                            uploading = false,
                            message = if (ok) "上传完成" else "上传失败，请检查 Cookie 是否过期",
                            progress = 0
                        )
                    }
                }
            }
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
                    if (idx >= 0) c.getString(idx) else "upload_${System.currentTimeMillis()}"
                }
            }.getOrDefault("upload_${System.currentTimeMillis()}")

            val target = File(context.cacheDir, "uploads/$name")
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        }.getOrNull()
    }
}
