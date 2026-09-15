package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.FavoriteShare
import kotlinx.coroutines.flow.Flow

/** 分享收藏夹（Room 持久化） */
interface ShareRepository {

    fun observeFavorites(): Flow<List<FavoriteShare>>

    suspend fun addFavorite(url: String, name: String, remark: String = "")

    suspend fun removeFavorite(url: String)

    /** 修改备注（传空字符串即清空） */
    suspend fun updateRemark(url: String, remark: String)

    /** 修改名称 */
    suspend fun updateName(url: String, name: String)

    /** 置顶 / 取消置顶 */
    suspend fun setPinned(url: String, pinned: Boolean)
}
