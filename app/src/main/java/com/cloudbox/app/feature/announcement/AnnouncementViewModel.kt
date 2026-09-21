package com.cloudbox.app.feature.announcement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.Announcement
import com.cloudbox.app.core.domain.repository.AnnouncementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnnouncementUiState(
    val loading: Boolean = false,
    val announcements: List<Announcement> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class AnnouncementViewModel @Inject constructor(
    private val repository: AnnouncementRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnnouncementUiState())
    val uiState: StateFlow<AnnouncementUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        if (_uiState.value.loading) return
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.fetch().fold(
                onSuccess = { list ->
                    val sorted = list.sortedWith(
                        compareByDescending<Announcement> { it.pinned }
                            .thenByDescending { it.date.orEmpty() }
                    )
                    _uiState.update { it.copy(loading = false, announcements = sorted, error = null) }
                    // 打开即已读：记下最新一条的 id，使红点消除（对齐"打开页面清红点"）
                    sorted.firstOrNull()?.let { repository.markRead(it.id) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(loading = false, error = e.message ?: "公告加载失败") }
                }
            )
        }
    }
}
