package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.core.domain.model.FileListPage
import com.cloudbox.app.core.domain.model.ShareInfo

/**
 * 文件管理仓库：列表浏览（带缓存）+ 全部管理操作 + 回收站。
 *
 * 说明：移动功能仅支持文件。官网实测（2026-09）文件夹 ⋯ 菜单无「移动」项，
 * 服务端只提供 task=20（file_id），无文件夹移动接口；
 * LanZouCloud-API 用"新建+逐个移文件+删除"模拟，需三次写操作、中途失败会丢数据，风险高，
 * 本客户端不采用。
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

    /** 设置文件提取码 task=23（pwd 为空 = 关闭，注意非会员可能不允许关闭） */
    suspend fun setFilePasswd(fileId: Long, pwd: String): Result<Unit>

    /**
     * 设置文件夹提取码 task=16。
     *
     * ⚠️ 与文件用不同接口：官网文件夹菜单里的「设置访问密码」调的是 task=16
     * （`fol_pwdgo`），字段同样是 shows / shownames，但 id 字段是 folder_id。
     * 实测非会员会返回 `{"zt":null,"info":"此功能仅会員使用（个人中心 - 会员个性化）"}`，
     * 属于账号等级限制而非接口错误，失败时把服务端原话透传给用户。
     */
    suspend fun setDirPasswd(folderId: Long, pwd: String): Result<Unit>

    /**
     * 设置文件夹描述（task=4，`folder_description`）。
     *
     * 官网文件夹「修改资料(话说)」把改名 + 改描述放在同一个表单（`fol_desgo`），
     * 提交时 `folder_name` 与 `folder_description` 一起发。
     * 这里为了不动现有重命名流程，单独提供一个只改描述的入口 ——
     * name 传原值即可（task=4 是整体覆盖，不传 name 会把文件夹名清掉）。
     */
    suspend fun setDirDesc(folder: CloudFile, desc: String): Result<Unit>

    /**
     * 读取文件夹描述（task=18，`info.des`）。
     *
     * 官网 `fol_des()` 用 task=18 一次性取回 `{name, des}` 回填资料弹窗 ——
     * 与文件用 task=12 是两套接口，别混。
     */
    suspend fun getDirDesc(folderId: Long): Result<String>

    /** 设置文件描述（⚠️ 设置后不能置空） */
    suspend fun setFileDesc(fileId: Long, desc: String): Result<Unit>

    /**
     * 读取文件描述（task=12，返回 info 字段）。
     *
     * 为什么要它：设置描述用的是 task=11，若打开弹窗时不回填原值，用户只能盲改。
     * 原版网页端 `f_des()` 正是先用 task=12 把原描述读回来填进输入框，
     * 保存时才调 `f_desgo()` → task=11。这里对齐同一套流程。
     *
     * 文件从未设置过描述时，服务端 info 可能为 null / 空串，一律返回 ""。
     */
    suspend fun getFileDesc(fileId: Long): Result<String>

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

    /**
     * 查看回收站里某个文件夹的内容（不恢复、不删除，只是看一眼）。
     *
     * 对齐原版 recycle.lua 的「查看文件夹弹窗」：回收站里的文件夹是个黑盒，
     * 用户删除前想确认"这里面装着什么"再决定恢复还是彻底删。
     * 走 `mydisk.php?item=recycle&action=show_files&folder_id=<id>` 取 HTML 再解析。
     */
    suspend fun getRecycleFolderItems(folderId: Long): Result<List<CloudFile>>
}

/** 回收站内容（HTML 解析结果） */
data class RecycleItems(
    val files: List<CloudFile>,
    val folders: List<CloudFile>
)
