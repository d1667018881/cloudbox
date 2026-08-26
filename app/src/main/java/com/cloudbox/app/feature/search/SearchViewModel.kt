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
    val searching: Boolean = false,
    val syncing: Boolean = false,
    val syncProgress: Int = 0,
    val syncTotal: Int = 0,
    val message: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    fun onKeywordChange(kw: String) {
        _uiState.update { it.copy(keyword = kw) }
        if (kw.trim().length >= 1) search(kw) else _uiState.update { it.copy(results = emptyList()) }
    }

    fun search(kw: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(searching = true) }
            val results = searchRepository.search(kw)
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

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}
