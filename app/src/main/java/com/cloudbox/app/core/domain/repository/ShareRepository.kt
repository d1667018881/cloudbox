package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.FavoriteShare
import kotlinx.coroutines.flow.Flow

/** 分享收藏夹（Room 持久化） */
interface ShareRepository {

    fun observeFavorites(): Flow<List<FavoriteShare>>

    suspend fun addFavorite(url: String, name: String, remark: String = "")

    suspend fun removeFavorite(url: String)
}
