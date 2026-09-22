package com.cloudbox.app.feature.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.StarredFolder
import com.cloudbox.app.core.domain.repository.FileRepository
import com.cloudbox.app.core.domain.repository.StarredFolderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 星标文件夹页状态 */
data class StarredFoldersUiState(
    val items: List<StarredFolder> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
    /** 正在编辑（名称/备注）的条目，null = 未打开编辑框 */
    val editTarget: StarredFolder? = null,
    val editName: String = "",
    val editRemark: String = "",
    /** 待二次确认「取消星标」的条目 */
    val confirmRemove: StarredFolder? = null,
    /** 展示「星标文件夹说明」弹窗 */
    val showHelp: Boolean = false
)

/**
 * 星标文件夹页 ViewModel。
 *
 * 数据源是本地 Room（按账号分桶），所以进入页面不需要网络请求；
 * 列表用 [StarredFolderRepository.observe] 订阅，取消星标 / 编辑后自动刷新，
 * 无需手工重载。
 */
@HiltViewModel
class StarredFoldersViewModel @Inject constructor(
    private val starredRepo: StarredFolderRepository,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StarredFoldersUiState())
    val uiState: StateFlow<StarredFoldersUiState> = _uiState.asStateFlow()

    /** 当前账号 uid（进页面时解析一次；切换账号会重建 ViewModel） */
    private var uid: String? = null

    init {
        viewModelScope.launch {
            val current = fileRepository.currentUid()
            uid = current
            if (current.isNullOrBlank()) {
                _uiState.update { it.copy(loading = false, message = "未登录，无法读取星标数据") }
            } else {
                starredRepo.observe(current).collect { list ->
                    _uiState.update { it.copy(items = list, loading = false) }
                }
            }
        }
    }

    // ==================== 编辑名称 / 备注 ====================

    fun openEdit(item: StarredFolder) = _uiState.update {
        it.copy(editTarget = item, editName = item.name, editRemark = item.remark)
    }

    fun setEditName(v: String) = _uiState.update { it.copy(editName = v) }

    fun setEditRemark(v: String) = _uiState.update { it.copy(editRemark = v) }

    fun dismissEdit() = _uiState.update { it.copy(editTarget = null) }

    fun saveEdit() {
        val s = _uiState.value
        val target = s.editTarget ?: return
        val u = uid ?: return
        val newName = s.editName.trim().ifBlank { target.name }
        if (newName == target.name && s.editRemark == target.remark) {
            _uiState.update { it.copy(editTarget = null) }
            return
        }
        viewModelScope.launch {
            if (newName != target.name) starredRepo.rename(u, target.folderId, newName)
            if (s.editRemark != target.remark) starredRepo.setRemark(u, target.folderId, s.editRemark)
            _uiState.update { it.copy(editTarget = null, message = "已保存") }
        }
    }

    // ==================== 取消星标（二次确认） ====================

    fun askRemove(item: StarredFolder) = _uiState.update { it.copy(confirmRemove = item) }

    fun dismissRemove() = _uiState.update { it.copy(confirmRemove = null) }

    fun confirmRemove() {
        val target = _uiState.value.confirmRemove ?: return
        val u = uid ?: return
        viewModelScope.launch {
            starredRepo.unstar(u, target.folderId)
            _uiState.update { it.copy(confirmRemove = null, message = "已取消星标") }
        }
    }

    // ==================== 排序 / 说明 ====================

    /** 按名称排序并把结果落盘（原版行为） */
    fun sortByName() {
        val u = uid ?: return
        viewModelScope.launch {
            starredRepo.sortByName(u)
            _uiState.update { it.copy(message = "已按名称排序") }
        }
    }

    fun showHelp() = _uiState.update { it.copy(showHelp = true) }

    fun dismissHelp() = _uiState.update { it.copy(showHelp = false) }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}
