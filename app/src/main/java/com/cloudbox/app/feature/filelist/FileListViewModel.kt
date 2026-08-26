package com.cloudbox.app.feature.filelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.core.domain.model.ShareInfo
import com.cloudbox.app.core.domain.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 文件列表 UI 状态 */
data class FileListUiState(
    val folderStack: List<Pair<Long, String>> = listOf(-1L to "根目录"), // 面包屑
    val files: List<CloudFile> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val gridMode: Boolean = false,
    val selectionMode: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val shareResult: ShareInfo? = null,
    val message: String? = null
) {
    val currentFolderId: Long get() = folderStack.last().first
    val currentFolderName: String get() = folderStack.last().second
}

@HiltViewModel
class FileListViewModel @Inject constructor(
    val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileListUiState())
    val uiState: StateFlow<FileListUiState> = _uiState.asStateFlow()

    private var page = 1

    init {
        refresh()
    }

    fun refresh() {
        page = 1
        loadPage(append = false)
    }

    fun loadMore() {
        val s = _uiState.value
        if (s.loading || s.loadingMore || s.files.isEmpty()) return
        page++
        loadPage(append = true)
    }

    private fun loadPage(append: Boolean) {
        val folderId = _uiState.value.currentFolderId
        viewModelScope.launch {
            if (append) {
                _uiState.update { it.copy(loadingMore = true) }
            } else {
                _uiState.update { it.copy(loading = true, error = null) }
            }
            fileRepository.getPage(folderId, page).onSuccess { listPage ->
                _uiState.update {
                    it.copy(
                        files = if (append) it.files + listPage.files else listPage.folders + listPage.files,
                        loading = false,
                        loadingMore = false,
                        hasMore = listPage.hasMore
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(loading = false, loadingMore = false, error = e.message ?: "加载失败")
                }
            }
        }
    }

    /** 进入子文件夹 */
    fun enterFolder(folderId: Long, name: String) {
        _uiState.update { it.copy(folderStack = it.folderStack + (folderId to name)) }
        page = 1
        loadPage(append = false)
    }

    /** 面包屑跳转（截断栈） */
    fun navigateTo(index: Int) {
        _uiState.update {
            it.copy(folderStack = it.folderStack.take(index + 1), selectionMode = false, selected = emptySet())
        }
        page = 1
        loadPage(append = false)
    }

    fun back(): Boolean {
        val s = _uiState.value
        if (s.selectionMode) {
            exitSelection()
            return true
        }
        if (s.folderStack.size > 1) {
            navigateTo(s.folderStack.size - 2)
            return true
        }
        return false
    }

    fun toggleGrid() = _uiState.update { it.copy(gridMode = !it.gridMode) }

    fun enterSelection(file: CloudFile) {
        _uiState.update { it.copy(selectionMode = true, selected = setOf(file.id)) }
    }

    fun toggleSelect(id: Long) {
        _uiState.update {
            val sel = it.selected.toMutableSet()
            if (!sel.add(id)) sel.remove(id)
            it.copy(selected = sel)
        }
    }

    fun exitSelection() = _uiState.update { it.copy(selectionMode = false, selected = emptySet()) }

    // ==================== 操作 ====================

    fun createFolder(name: String) {
        viewModelScope.launch {
            fileRepository.createFolder(_uiState.value.currentFolderId, name)
                .onSuccess { refresh() }
                .onFailure { e -> _uiState.update { it.copy(message = "新建失败：${e.message}") } }
        }
    }

    fun rename(file: CloudFile, newName: String) {
        viewModelScope.launch {
            fileRepository.rename(file, newName)
                .onSuccess { refresh() }
                .onFailure { e -> _uiState.update { it.copy(message = "重命名失败：${e.message}") } }
        }
    }

    fun deleteSelected() {
        val s = _uiState.value
        val files = s.files.filter { it.id in s.selected }
        val fileIds = files.filter { !it.isFolder }.map { it.id }
        val folderIds = files.filter { it.isFolder }.map { it.id }
        viewModelScope.launch {
            fileRepository.delete(fileIds, folderIds)
                .onSuccess {
                    exitSelection()
                    refresh()
                }
                .onFailure { e -> _uiState.update { it.copy(message = "删除失败：${e.message}") } }
        }
    }

    fun moveSelected(targetFolderId: Long) {
        val s = _uiState.value
        val selectedFiles = s.files.filter { it.id in s.selected }
        val fileIds = selectedFiles.filter { !it.isFolder }.map { it.id }
        val folderCount = selectedFiles.count { it.isFolder }
        // #19 修复：明确提示文件夹不支持移动（官方无接口），不再静默忽略
        if (folderCount > 0) {
            _uiState.update { it.copy(message = "文件夹暂不支持移动（官方无接口），仅移动 ${fileIds.size} 个文件") }
        }
        if (fileIds.isEmpty()) {
            exitSelection()
            return
        }
        viewModelScope.launch {
            fileRepository.moveFiles(fileIds, targetFolderId)
                .onSuccess {
                    exitSelection()
                    refresh()
                }
                .onFailure { e -> _uiState.update { it.copy(message = "移动失败：${e.message}") } }
        }
    }

    fun setPasswd(file: CloudFile, pwd: String) {
        viewModelScope.launch {
            fileRepository.setFilePasswd(file.id, pwd)
                .onSuccess { _uiState.update { it.copy(message = "提取码已设置") } }
                .onFailure { e -> _uiState.update { it.copy(message = "设置失败：${e.message}") } }
        }
    }

    fun setDesc(file: CloudFile, desc: String) {
        viewModelScope.launch {
            fileRepository.setFileDesc(file.id, desc)
                .onSuccess { _uiState.update { it.copy(message = "描述已设置") } }
                .onFailure { e -> _uiState.update { it.copy(message = "设置失败：${e.message}") } }
        }
    }

    fun getShare(file: CloudFile) {
        viewModelScope.launch {
            val result = if (file.isFolder) {
                fileRepository.getDirShare(file.id)
            } else {
                fileRepository.getFileShare(file.id)
            }
            result.onSuccess { share ->
                _uiState.update { it.copy(shareResult = share) }
            }.onFailure { e ->
                _uiState.update { it.copy(message = "获取分享失败：${e.message}") }
            }
        }
    }

    fun dismissShare() = _uiState.update { it.copy(shareResult = null) }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}
