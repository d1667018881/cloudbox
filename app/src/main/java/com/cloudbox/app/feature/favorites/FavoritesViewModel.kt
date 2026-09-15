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

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val shareRepository: ShareRepository
) : ViewModel() {

    private val _favorites = MutableStateFlow<List<FavoriteShare>>(emptyList())
    val favorites: StateFlow<List<FavoriteShare>> = _favorites.asStateFlow()

    /** 一次性提示（复制成功等） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            shareRepository.observeFavorites().collect { _favorites.value = it }
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
            _message.value = "已保存"
        }
    }

    /** 置顶 / 取消置顶 */
    fun togglePin(fav: FavoriteShare) {
        viewModelScope.launch {
            shareRepository.setPinned(fav.shareUrl, !fav.pinned)
            _message.value = if (fav.pinned) "已取消置顶" else "已置顶"
        }
    }

    fun notify(text: String) {
        _message.value = text
    }

    fun dismissMessage() {
        _message.update { null }
    }
}
