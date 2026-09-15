package com.cloudbox.app.feature.recycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.core.domain.repository.FileRepository
import com.cloudbox.app.core.domain.repository.RecycleItems
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 回收站 UI 状态 */
data class RecycleUiState(
    val items: RecycleItems = RecycleItems(emptyList(), emptyList()),
    val loading: Boolean = false,
    val message: String? = null,
    /** 正在查看的回收站文件夹弹窗（null = 未打开） */
    val folderDialog: FolderDialogState? = null
)

/** 回收站文件夹内容弹窗状态 */
data class FolderDialogState(
    val folderId: Long,
    val folderName: String,
    val loading: Boolean = true,
    val files: List<CloudFile> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class RecycleViewModel @Inject constructor(
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecycleUiState())
    val uiState: StateFlow<RecycleUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            fileRepository.getRecycleItems()
                .onSuccess { items -> _uiState.update { it.copy(items = items, loading = false) } }
                .onFailure { e -> _uiState.update { it.copy(loading = false, message = e.message) } }
        }
    }

    fun restore(file: CloudFile) {
        viewModelScope.launch {
            val r = if (file.isFolder) {
                fileRepository.restoreItems(emptyList(), listOf(file.id))
            } else {
                fileRepository.restoreItems(listOf(file.id), emptyList())
            }
            r.onSuccess { load() }.onFailure { e -> _uiState.update { it.copy(message = e.message) } }
        }
    }

    fun deleteComplete(file: CloudFile) {
        viewModelScope.launch {
            val r = if (file.isFolder) {
                fileRepository.deleteCompleteItems(emptyList(), listOf(file.id))
            } else {
                fileRepository.deleteCompleteItems(listOf(file.id), emptyList())
            }
            r.onSuccess { load() }.onFailure { e -> _uiState.update { it.copy(message = e.message) } }
        }
    }

    fun restoreAll() {
        viewModelScope.launch {
            fileRepository.restoreAll()
                .onSuccess { load() }
                .onFailure { e -> _uiState.update { it.copy(message = e.message) } }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            fileRepository.clearRecycle()
                .onSuccess { load() }
                .onFailure { e -> _uiState.update { it.copy(message = e.message) } }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    // ==================== 查看文件夹内容（只读） ====================

    /**
     * 打开"查看文件夹内容"弹窗。
     *
     * 回收站里的文件夹是个黑盒 —— 用户要决定"恢复还是彻底删"，
     * 得先知道里面装了什么。原版 recycle.lua 就有这个弹窗。
     */
    fun openFolderDialog(folder: CloudFile) {
        _uiState.update {
            it.copy(
                folderDialog = FolderDialogState(
                    folderId = folder.id,
                    folderName = folder.name,
                    loading = true
                )
            )
        }
        viewModelScope.launch {
            fileRepository.getRecycleFolderItems(folder.id)
                .onSuccess { files ->
                    _uiState.update { st ->
                        st.copy(folderDialog = st.folderDialog?.copy(loading = false, files = files, error = null))
                    }
                }
                .onFailure { e ->
                    _uiState.update { st ->
                        st.copy(folderDialog = st.folderDialog?.copy(loading = false, error = e.message ?: "加载失败"))
                    }
                }
        }
    }

    fun closeFolderDialog() = _uiState.update { it.copy(folderDialog = null) }
}
