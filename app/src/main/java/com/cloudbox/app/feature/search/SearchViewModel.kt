package com.cloudbox.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.core.domain.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 搜索页 UI 状态 */
data class SearchUiState(
    val keyword: String = "",
    val results: List<CloudFile> = emptyList(),
    /** V56 搜索模式（原版 precise_search 默认 true=按后缀搜） */
    val suffixMode: Boolean = true,
    val searching: Boolean = false,
    val syncing: Boolean = false,
    val syncProgress: Int = 0,
    val syncTotal: Int = 0,
    val message: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    /** V53 去 private：Screen 侧按需读取（搜索模式开关） */
    val settingsStore: com.cloudbox.app.core.data.local.datastore.SettingsStore
) : ViewModel() {

    init {
        // V56：装载搜索模式（原版 precise_search，默认后缀模式）
        viewModelScope.launch {
            val m = settingsStore.preciseSearch.first()
            _uiState.update { it.copy(suffixMode = m) }
        }
    }

    /** V56：切换搜索模式并重搜（原版=搜索框旁按钮切换「搜索后缀/模糊搜索」） */
    fun toggleMode() {
        val next = !_uiState.value.suffixMode
        viewModelScope.launch {
            settingsStore.setPreciseSearch(next)
            _uiState.update { it.copy(suffixMode = next) }
            val kw = _uiState.value.keyword
            if (kw.isNotBlank()) search(kw)
        }
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    fun onKeywordChange(kw: String) {
        _uiState.update { it.copy(keyword = kw) }
        if (kw.trim().length >= 1) search(kw) else _uiState.update { it.copy(results = emptyList()) }
    }

    fun search(kw: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(searching = true) }
            val results = searchRepository.search(kw, _uiState.value.suffixMode)
            _uiState.update { it.copy(results = results, searching = false) }
        }
    }

    /** 全盘索引同步（后台自动同步；可手动触发） */
    fun syncAll() {
        if (_uiState.value.syncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(syncing = true) }
            searchRepository.syncAll { done, total ->
                _uiState.update { it.copy(syncProgress = done, syncTotal = total) }
            }.onSuccess { count ->
                _uiState.update { it.copy(syncing = false, message = "索引完成：$count 个文件") }
            }.onFailure { e ->
                _uiState.update { it.copy(syncing = false, message = "同步失败：${e.message}") }
            }
        }
    }

    /**
     * 进入搜索页时调用：索引为空则先跑一次全盘同步。
     *
     * 旧实现只在手动点右上角刷新时才同步 —— 首次搜索必然是空结果，
     * 用户看到的表象是「搜索能搜但不能用」，实际是压根没有索引。
     */
    fun ensureIndexed() {
        viewModelScope.launch {
            if (searchRepository.isSynced()) return@launch
            syncAll()
        }
    }

    fun showMessage(text: String) = _uiState.update { it.copy(message = text) }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}
