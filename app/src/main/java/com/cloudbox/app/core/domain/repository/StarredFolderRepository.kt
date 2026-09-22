package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.StarredFolder
import kotlinx.coroutines.flow.Flow

/**
 * 星标文件夹仓库（对齐原版「星标文件夹」）。
 *
 * 定位：把自己网盘里的常用文件夹聚合成一个快捷入口。与「收藏夹」不同 ——
 * 收藏夹存的是**分享链接**（别人的链接也能收），星标存的是**自己盘内的目录 id**。
 *
 * [uid] 处处显式传入而不是从登录态内部读取：星标数据是**按账号分桶**的，
 * 而退出登录 / 删除账号时需要清掉「某个特定账号」的星标（那个账号可能已经不
 * 是当前账号了），只有显式传 uid 才表达得出来。
 */
interface StarredFolderRepository {

    /** 订阅某账号的星标列表 */
    fun observe(uid: String): Flow<List<StarredFolder>>

    /** 该文件夹是否已星标 */
    suspend fun isStarred(uid: String, folderId: Long): Boolean

    /**
     * 添加星标。
     *
     * 已星标时**不覆盖**已有的名称快照 —— 原版明确「星标后如此文件夹信息有修改，
     * 星标文件夹中的信息不会更新」，重复星标不该悄悄把用户看到的名字改掉。
     */
    suspend fun star(uid: String, folderId: Long, name: String)

    /** 取消星标 */
    suspend fun unstar(uid: String, folderId: Long)

    /** 重命名星标项（只改本地快照） */
    suspend fun rename(uid: String, folderId: Long, name: String)

    /** 设置备注 */
    suspend fun setRemark(uid: String, folderId: Long, remark: String)

    /** 按名称排序并把结果落盘（原版语义） */
    suspend fun sortByName(uid: String)

    /** 清空某账号的全部星标（退出登录 / 删除账号时调用） */
    suspend fun clearForAccount(uid: String)
}
