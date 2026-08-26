package com.cloudbox.app.core.data.repository

import com.cloudbox.app.core.data.local.db.AppDatabase
import com.cloudbox.app.core.data.local.db.FavoriteShareEntity
import com.cloudbox.app.core.domain.model.FavoriteShare
import com.cloudbox.app.core.domain.repository.ShareRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareRepositoryImpl @Inject constructor(
    private val db: AppDatabase
) : ShareRepository {

    override fun observeFavorites(): Flow<List<FavoriteShare>> =
        db.favoriteShareDao().observeAll().map { list ->
            list.map { FavoriteShare(it.shareUrl, it.name, it.remark, it.createdAt) }
        }

    override suspend fun addFavorite(url: String, name: String, remark: String) {
        db.favoriteShareDao().insert(FavoriteShareEntity(url, name, remark))
    }

    override suspend fun removeFavorite(url: String) {
        db.favoriteShareDao().delete(url)
    }
}
