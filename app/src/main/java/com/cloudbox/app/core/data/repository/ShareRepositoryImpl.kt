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
            list.map { FavoriteShare(it.shareUrl, it.name, it.remark, it.createdAt, it.pinned) }
        }

    override suspend fun addFavorite(url: String, name: String, remark: String) {
        // ⚠️ 重新收藏同一条链接时不要覆盖已有的置顶状态——
        //    REPLACE 会整行替换，如果这里传默认 pinned=false，
        //    用户"重新收藏"会把置顶悄悄清掉。先读旧值，有则沿用。
        val old = db.favoriteShareDao().get(url)
        db.favoriteShareDao().insert(
            FavoriteShareEntity(
                shareUrl = url,
                name = name,
                remark = remark,
                pinned = old?.pinned ?: false
            )
        )
    }

    override suspend fun removeFavorite(url: String) {
        db.favoriteShareDao().delete(url)
    }

    override suspend fun updateRemark(url: String, remark: String) {
        db.favoriteShareDao().updateRemark(url, remark)
    }

    override suspend fun updateName(url: String, name: String) {
        db.favoriteShareDao().updateName(url, name)
    }

    override suspend fun setPinned(url: String, pinned: Boolean) {
        db.favoriteShareDao().updatePinned(url, pinned)
    }
}
