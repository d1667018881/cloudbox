package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.core.domain.model.FileListPage
import com.cloudbox.app.core.domain.model.ShareInfo

/**
 * 文件管理仓库：列表浏览（带缓存）+ 全部管理操作 + 回收站。
 *
 * 说明：移动文件夹官方无直接接口（LanZouCloud-API 用"新建+逐个移文件+删除"模拟，
 * 风险高），本客户端仅支持文件批量移动，文件夹移动在 UI 层提示不支持。
 */
interface FileRepository {

    /** 当前账号 uid（供 UI 显示与缓存隔离） */
    suspend fun currentUid(): String?

    /** 获取某目录一页内容（文件夹 + 文件），page 从 1 开始 */
    suspend fun getPage(folderId: Long, page: Int): Result<FileListPage>

    /** 获取全部文件夹列表（移动目标选择） */
    suspend fun getAllFolders(): Result<List<Pair<Long, String>>>

    /** 新建文件夹，返回新文件夹 id（失败返回 null） */
    suspend fun createFolder(parentId: Long, name: String): Result<Long?>

    suspend fun rename(file: CloudFile, newName: String): Result<Unit>

    /** 批量移动文件（folderId=-1 表示根目录） */
    suspend fun moveFiles(fileIds: List<Long>, targetFolderId: Long): Result<Unit>

    /** 批量删除（fileIds 与 folderIds 可同时传），入回收站 */
    suspend fun delete(fileIds: List<Long>, folderIds: List<Long>): Result<Unit>

    /** 设置文件提取码（pwd 为空 = 关闭，注意非会员可能不允许关闭） */
    suspend fun setFilePasswd(fileId: Long, pwd: String): Result<Unit>

    /** 设置文件描述（⚠️ 设置后不能置空） */
    suspend fun setFileDesc(fileId: Long, desc: String): Result<Unit>

    /** 获取文件分享链接 */
    suspend fun getFileShare(fileId: Long): Result<ShareInfo>

    /** 获取文件夹分享链接 */
    suspend fun getDirShare(folderId: Long): Result<ShareInfo>

    // ==================== 回收站（mydisk.php HTML） ====================

    /** 列出回收站内容（文件 + 文件夹，解析 HTML） */
    suspend fun getRecycleItems(): Result<RecycleItems>

    /** 恢复单项（fileIds/folderIds） */
    suspend fun restoreItems(fileIds: List<Long>, folderIds: List<Long>): Result<Unit>

    /** 彻底删除单项 */
    suspend fun deleteCompleteItems(fileIds: List<Long>, folderIds: List<Long>): Result<Unit>

    /** 恢复全部 */
    suspend fun restoreAll(): Result<Unit>

    /** 清空回收站 */
    suspend fun clearRecycle(): Result<Unit>
}

/** 回收站内容（HTML 解析结果） */
data class RecycleItems(
    val files: List<CloudFile>,
    val folders: List<CloudFile>
)
