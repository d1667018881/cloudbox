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

    // ==================== V30：检查收藏文件夹更新（对齐原版 v1.3.4.9） ====================

    /**
     * 逐个检查收藏夹里的**文件夹**型条目是否有更新。
     *
     * 对齐原版 `favorites.lua` 的「检查收藏文件夹更新」：
     * 原版只处理 `open_link_history[i].type == "folder"` 的条目，
     * 逐个 GET 分享页 → POST filemoreajax.php 比对文件列表，有差异就置红点。
     *
     * @param onProgress (已检查数, 总数, 当前项名称) —— 供 UI 显示进度
     * @return (检查成功数, 检测到更新的数量, 总数)
     */
    suspend fun checkFolderUpdates(
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): Result<UpdateCheckSummary>

    /** 清除某条收藏的「有更新」标记（用户点开看过之后调用，对齐原版 favorites.lua fn20） */
    suspend fun clearUpdateFlag(url: String)

    /** 修正收藏的类型与提取码（重新收藏时调用，保证 folder 型能被更新检查覆盖） */
    suspend fun correctKind(url: String, kind: String, pass: String)
}

/** 一次「检查收藏文件夹更新」的结果汇总 */
data class UpdateCheckSummary(
    /** 检查成功（拿到页面并比对完）的条目数 */
    val checked: Int,
    /** 其中检测到有更新的条目数 */
    val updated: Int,
    /** 因网络/失效等原因跳过的条目数 */
    val failed: Int,
    /** 发生失败的条目名（用于提示用户具体哪几条没查到） */
    val failedNames: List<String> = emptyList()
)
