package com.cloudbox.app.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.FavoriteShare
import com.cloudbox.app.core.domain.repository.ShareRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 收藏夹页面状态。
 *
 * V30（对齐原版 v1.3.4.9）新增 [checking] / [checkDone] / [checkTotal] / [checkCurrent]：
 * 原版用 `activity.getGlobalData().checkingUpdate` 全局标志 + `Ticker` 1 秒轮询
 * 驱动「检查收藏文件夹更新」的进度与红点刷新（favorites.lua:1188-1201），
 * 这里用等价的 Compose 状态表达。
 */
data class FavoritesUiState(
    val favorites: List<FavoriteShare> = emptyList(),
    /** V49：当前过滤的分组（null = 全部，"" = 未分组） */
    val currentFolder: String? = null,
    /** 是否正在检查（原版 checkingUpdate） */
    val checking: Boolean = false,
    val checkDone: Int = 0,
    val checkTotal: Int = 0,
    val checkCurrent: String = "",
    val message: String? = null
) {
    /** 从条目聚合出的分组名列表（按名称排序；无独立表，见 Entity.folder 注释） */
    val folders: List<String>
        get() = favorites.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
}

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val shareRepository: ShareRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            shareRepository.observeFavorites().collect { list ->
                _state.update { it.copy(favorites = list) }
            }
        }
    }

    fun remove(url: String) {
        viewModelScope.launch { shareRepository.removeFavorite(url) }
    }

    /** 修改名称与备注（两个字段一起保存，避免两次写库出现中间态） */
    fun edit(url: String, name: String, remark: String) {
        viewModelScope.launch {
            if (name.isNotBlank()) shareRepository.updateName(url, name.trim())
            shareRepository.updateRemark(url, remark.trim())
            _state.update { it.copy(message = "已保存") }
        }
    }

    // ==================== V49：收藏夹分组（对齐原版 bookmark_folder） ====================

    /** 切换分组过滤（null=全部，""=未分组） */
    fun selectFolder(folder: String?) {
        _state.update { it.copy(currentFolder = folder) }
    }

    /** 移动收藏到分组（空串=移回未分组） */
    fun move(url: String, folder: String) {
        viewModelScope.launch {
            shareRepository.moveFavorite(url, folder)
            _state.update { it.copy(message = if (folder.isBlank()) "已移出分组" else "已移入「${folder.trim()}」") }
        }
    }

    /** 分组重命名 */
    fun renameFolder(oldName: String, newName: String) {
        viewModelScope.launch {
            shareRepository.renameFolder(oldName, newName)
            _state.update { state ->
                state.copy(
                    currentFolder = if (state.currentFolder == oldName) newName else state.currentFolder,
                    message = "分组已重命名"
                )
            }
        }
    }

    /** 删除分组（组内条目移回未分组） */
    fun dissolveFolder(name: String) {
        viewModelScope.launch {
            shareRepository.dissolveFolder(name)
            _state.update { state ->
                state.copy(
                    currentFolder = if (state.currentFolder == name) null else state.currentFolder,
                    message = "分组已删除（组内收藏已移回未分组）"
                )
            }
        }
    }

    /** 置顶 / 取消置顶 */
    fun togglePin(fav: FavoriteShare) {
        viewModelScope.launch {
            shareRepository.setPinned(fav.shareUrl, !fav.pinned)
            _state.update { it.copy(message = if (fav.pinned) "已取消置顶" else "已置顶") }
        }
    }

    /**
     * 检查所有收藏文件夹是否有更新（原版「检查收藏文件夹更新」按钮）。
     *
     * 结果文案对齐原版常量（home_func.lua proto[35]）：
     *   `" 发现 " + n + " 项更新"` / `" 未检测到更新"` / `"检查更新失败"`
     */
    fun checkFolderUpdates() {
        if (_state.value.checking) {
            // 原版有「请等待当前任务结束」的互斥提示，这里保持一致
            _state.update { it.copy(message = "请等待当前任务结束") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(checking = true, checkDone = 0, checkTotal = 0, checkCurrent = "")
            }
            val result = shareRepository.checkFolderUpdates { done, total, current ->
                _state.update {
                    it.copy(checkDone = done, checkTotal = total, checkCurrent = current)
                }
            }
            _state.update { st ->
                val msg = result.fold(
                    onSuccess = { s ->
                        when {
                            s.checked == 0 && s.failed > 0 -> "检查更新失败"
                            s.failed > 0 ->
                                "检查完成：发现 ${s.updated} 项更新，${s.failed} 项检测失败"
                            s.updated > 0 -> "发现 ${s.updated} 项更新"
                            else -> "未检测到更新"
                        }
                    },
                    onFailure = { "检查更新失败：${it.message.orEmpty()}" }
                )
                st.copy(checking = false, checkCurrent = "", message = msg)
            }
        }
    }

    /** 用户点开某条之后清掉红点（原版 favorites.lua fn20：`update = false` + notifyItemChanged） */
    fun clearUpdateFlag(fav: FavoriteShare) {
        if (!fav.hasUpdate) return
        viewModelScope.launch { shareRepository.clearUpdateFlag(fav.shareUrl) }
    }

    fun notify(text: String) {
        _state.update { it.copy(message = text) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }
}
