package com.cloudbox.app.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.FavoriteShare
import com.cloudbox.app.core.domain.repository.ShareRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val shareRepository: ShareRepository
) : ViewModel() {

    private val _favorites = MutableStateFlow<List<FavoriteShare>>(emptyList())
    val favorites: StateFlow<List<FavoriteShare>> = _favorites.asStateFlow()

    init {
        viewModelScope.launch {
            shareRepository.observeFavorites().collect { _favorites.value = it }
        }
    }

    fun remove(url: String) {
        viewModelScope.launch { shareRepository.removeFavorite(url) }
    }
}
