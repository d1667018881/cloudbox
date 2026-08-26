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
    val message: String? = null
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
}
