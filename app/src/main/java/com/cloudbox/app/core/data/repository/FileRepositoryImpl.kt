package com.cloudbox.app.core.data.repository

import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.common.ApiError
import com.cloudbox.app.common.HtmlExtractor
import com.cloudbox.app.core.data.local.db.AppDatabase
import com.cloudbox.app.core.data.local.db.FileCacheEntity
import com.cloudbox.app.core.data.local.secure.AccountSecureStore
import com.cloudbox.app.core.data.remote.LanzouApiClient
import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.core.domain.model.FileListPage
import com.cloudbox.app.core.domain.model.ShareInfo
import com.cloudbox.app.core.domain.repository.FileRepository
import com.cloudbox.app.core.domain.repository.RecycleItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件管理仓库实现。
 *
 * 目录结构获取分两个接口（LanZouCloud-API 2025 源码确认）：
 * - task=47（doupload.php?uid=xxx）→ 子文件夹列表
 * - task=5 → 文件列表（pg 翻页，info=0 结束）
 *
 * 回收站：mydisk.php 是 HTML 交互（非 JSON），每次操作先 GET 对应 action 页
 * 提取 formhash，再 POST 表单（源码注释：此 formhash 与登录时不同，不可复用）。
 *
 * URL 构造说明：全部请求用占位 host（lz.dynamic.invalid），由
 * LanzouDomainInterceptor 按路径角色重写为当前配置的真实域名——
 * 这样回收站等手动 OkHttp 请求同样享受动态域名 + UA + Cookie + 重试。
 */
@Singleton
class FileRepositoryImpl @Inject constructor(
    private val apiClient: LanzouApiClient,
    private val db: AppDatabase,
    private val accountStore: AccountSecureStore
) : FileRepository {

    override suspend fun currentUid(): String? = accountStore.currentUid()

    private val api get() = apiClient.apiService
    private val okHttp get() = apiClient.okHttpClient

    /** 构造走统一拦截器的绝对 URL */
    private fun url(pathAndQuery: String) = "https://${AppConstants.PLACEHOLDER_HOST}/$pathAndQuery"

    override suspend fun getPage(folderId: Long, page: Int): Result<FileListPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uid = accountStore.currentUid() ?: throw ApiError.CookieExpired("未登录")
                // 1) 子文件夹（task=47，URL 带 uid）
                val dirsResp = api.getDirList(folderId = folderId, uid = uid)
                val folders = (dirsResp.text ?: emptyList()).map {
                    CloudFile(it.folId, it.name, true, null, null, it.onof, it.folderDes, folderId)
                } + (dirsResp.info ?: emptyList()).map {
                    CloudFile(it.folderId, it.name, true, null, null, null, null, folderId)
                }

                // 2) 文件（task=5，pg 翻页）
                val filesResp = api.getFileList(folderId = folderId, pg = page)
                val files = (filesResp.text ?: emptyList()).map {
                    CloudFile(it.id, it.nameAll, false, it.size, it.time, it.onof, it.isDes, folderId)
                }
                val hasMore = filesResp.info != 0

                // 3) 缓存：第 1 页整体刷新（含文件夹），翻页只追加文件
                if (page == 1) {
                    db.fileCacheDao().clearFolder(uid, folderId)
                }
                db.fileCacheDao().insertAll((folders + files).map {
                    FileCacheEntity(
                        accountUid = uid, parentId = folderId, id = it.id,
                        name = it.name, isFolder = it.isFolder, size = it.size,
                        time = it.time, onof = it.onof, isDes = it.isDes
                    )
                })

                FileListPage(folders, files, hasMore)
            }
        }

    override suspend fun getAllFolders(): Result<List<Pair<Long, String>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                api.getAllFolders().info?.map { it.folderId to it.folderName } ?: emptyList()
            }
        }

    override suspend fun createFolder(parentId: Long, name: String): Result<Long?> =
        withContext(Dispatchers.IO) {
            runCatching {
                // #7 修复：before 快照必须在 create 调用【之前】取（旧实现顺序写反，
                // 两次 getAllFolders 结果相同，diff 恒空 → 新文件夹 id 永远返回 null）
                val before = api.getAllFolders().info?.map { it.folderId to it.folderName } ?: emptyList()
                val resp = api.createFolder(parentId = parentId, folderName = name)
                if (resp.zt != 1) throw ApiError.Business(resp.zt, "新建文件夹失败")
                // task=2 不返回 id，用前后文件夹列表差异定位新文件夹（LanZouCloud-API 同款策略）
                val after = api.getAllFolders().info?.map { it.folderId to it.folderName } ?: emptyList()
                after.filter { it.second == name && it !in before }.firstOrNull()?.first
            }
        }

    override suspend fun rename(file: CloudFile, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = if (file.isFolder) {
                    api.renameDir(folderId = file.id, folderName = newName)
                } else {
                    api.renameFile(fileId = file.id, fileName = newName)
                }
                if (resp.zt != 1) throw ApiError.Business(resp.zt, "重命名失败")
            }
        }

    override suspend fun moveFiles(fileIds: List<Long>, targetFolderId: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                for (fid in fileIds) {
                    val resp = api.moveFile(fileId = fid, folderId = targetFolderId)
                    if (resp.zt != 1) throw ApiError.Business(resp.zt, "移动文件 $fid 失败")
                }
            }
        }

    override suspend fun delete(fileIds: List<Long>, folderIds: List<Long>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // #12 修复：批量删除同样加 1-3s 随机延时防风控
                // （R2 修复：删除重复循环；旧实现每个文件夹被删两遍、二次请求必然失败）
                var idx = 0
                for (fid in fileIds) {
                    if (idx++ > 0) delay(kotlin.random.Random.nextLong(1_000, 3_001))
                    val resp = api.deleteFile(fileId = fid)
                    if (resp.zt != 1) throw ApiError.Business(resp.zt, "删除文件 $fid 失败")
                }
                for (fid in folderIds) {
                    if (idx++ > 0) delay(kotlin.random.Random.nextLong(1_000, 3_001))
                    val resp = api.deleteDir(folderId = fid)
                    if (resp.zt != 1) throw ApiError.Business(resp.zt, "删除文件夹 $fid 失败")
                }
            }
        }

    override suspend fun setFilePasswd(fileId: Long, pwd: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // shows=0 关闭 / 1 开启（LanZouCloud-API：passwd_status = 0 if pwd=='' else 1）
                val resp = api.setFilePasswd(fileId = fileId, shows = if (pwd.isBlank()) 0 else 1, shownames = pwd)
                if (resp.zt != 1) throw ApiError.Business(resp.zt, "设置提取码失败")
            }
        }

    override suspend fun setFileDesc(fileId: Long, desc: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.setFileDesc(fileId = fileId, desc = desc)
                if (resp.zt != 1) throw ApiError.Business(resp.zt, "设置描述失败")
            }
        }

    override suspend fun getFileShare(fileId: Long): Result<ShareInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val info = api.getFileShareInfo(fileId = fileId).info
                    ?: throw ApiError.Business(-1, "分享信息为空")
                val fId = info.fId ?: throw ApiError.Business(-1, "无分享短码")
                // 分享链接 = is_newd(域名前缀) + '/' + f_id（LanZouCloud-API 拼凑规则）
                ShareInfo(
                    shareUrl = "${info.isNewd ?: ""}/$fId",
                    name = info.name ?: "",
                    pwd = info.pwd ?: "",
                    onof = info.onof ?: "0",
                    isFolder = false
                )
            }
        }

    override suspend fun getDirShare(folderId: Long): Result<ShareInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val info = api.getDirShareInfo(folderId = folderId).info
                    ?: throw ApiError.Business(-1, "分享信息为空")
                ShareInfo(
                    shareUrl = info.newUrl ?: "",
                    name = info.name ?: "",
                    pwd = info.pwd ?: "",
                    onof = info.onof ?: "0",
                    isFolder = true
                )
            }
        }

    // ==================== 回收站（mydisk.php HTML） ====================

    override suspend fun getRecycleItems(): Result<RecycleItems> = withContext(Dispatchers.IO) {
        runCatching {
            val html = getRecyclePage()
            // 回收站隐藏域：fl_sel_ids（文件 id）/ fd_sel_ids（文件夹 id）
            val fileIds = Regex("""fl_sel_ids[^>]*value="(\d+)"""").findAll(html)
                .map { it.groupValues[1].toLong() }.toList()
            val folderIds = Regex("""fd_sel_ids[^>]*value="(\d+)"""").findAll(html)
                .map { it.groupValues[1].toLong() }.toList()
            val doc = Jsoup.parse(html)
            // 文件名粗提取：包含该 id 的隐藏域所在行的 <a> 文本
            val files = fileIds.map { id ->
                val name = doc.select("input[value=$id]").firstOrNull()
                    ?.parent()?.select("a")?.firstOrNull()?.text() ?: "文件$id"
                CloudFile(id, name, false, null, null, null, null, -1)
            }
            val folders = folderIds.map { id ->
                val name = doc.select("input[value=$id]").firstOrNull()
                    ?.parent()?.select("a")?.firstOrNull()?.text() ?: "文件夹$id"
                CloudFile(id, name, true, null, null, null, null, -1)
            }
            RecycleItems(files, folders)
        }
    }

    override suspend fun restoreItems(fileIds: List<Long>, folderIds: List<Long>): Result<Unit> =
        recycleAction(
            actionOf = { id, isFolder ->
                if (isFolder) "folder_restore" to "folder_id=$id" else "file_restore" to "file_id=$id"
            },
            ids = fileIds.map { it to false } + folderIds.map { it to true },
            successKeyword = "恢复成功"
        )

    override suspend fun deleteCompleteItems(fileIds: List<Long>, folderIds: List<Long>): Result<Unit> =
        recycleAction(
            actionOf = { id, isFolder ->
                if (isFolder) "folder_delete_complete" to "folder_id=$id" else "file_delete_complete" to "file_id=$id"
            },
            ids = fileIds.map { it to false } + folderIds.map { it to true },
            successKeyword = "删除成功"
        )

    override suspend fun restoreAll(): Result<Unit> = recycleBulk("restore_all", "还原成功")

    override suspend fun clearRecycle(): Result<Unit> = recycleBulk("delete_all", "清空回收站成功")

    /** 单项操作：GET 取 formhash → POST 执行（#12：逐项加 1-3s 延时防风控） */
    private suspend fun recycleAction(
        actionOf: (Long, Boolean) -> Pair<String, String>,
        ids: List<Pair<Long, Boolean>>,
        successKeyword: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            for ((index, pair) in ids.withIndex()) {
                val (id, isFolder) = pair
                if (index > 0) delay(kotlin.random.Random.nextLong(1_000, 3_001))
                val (action, idParam) = actionOf(id, isFolder)
                val getHtml = okHttp.newCall(
                    Request.Builder()
                        .url(url("mydisk.php?item=recycle&action=$action&$idParam"))
                        .header("Referer", recycleReferer())
                        .build()
                ).execute().body?.string().orEmpty()
                val formhash = HtmlExtractor.extractFormhash(getHtml)
                    ?: throw ApiError.Business(-1, "无法获取 formhash")
                val body = FormBody.Builder()
                    .add("action", action)
                    .add("task", action)
                    .add(if (isFolder) "folder_id" else "file_id", id.toString())
                    .add("formhash", formhash)
                    .build()
                val respText = okHttp.newCall(
                    Request.Builder()
                        .url(url("mydisk.php?item=recycle"))
                        .header("Referer", recycleReferer())
                        .post(body)
                        .build()
                ).execute().body?.string().orEmpty()
                if (!respText.contains(successKeyword)) {
                    throw ApiError.Business(-1, "操作失败：${respText.take(80)}")
                }
            }
        }
    }

    /** 批量操作（restore_all / delete_all） */
    private suspend fun recycleBulk(action: String, successKeyword: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val getHtml = okHttp.newCall(
                    Request.Builder()
                        .url(url("mydisk.php?item=recycle&action=$action"))
                        .header("Referer", recycleReferer())
                        .build()
                ).execute().body?.string().orEmpty()
                val formhash = HtmlExtractor.extractFormhash(getHtml)
                    ?: throw ApiError.Business(-1, "无法获取 formhash")
                val body = FormBody.Builder()
                    .add("action", action)
                    .add("task", action)
                    .add("formhash", formhash)
                    .build()
                val respText = okHttp.newCall(
                    Request.Builder()
                        .url(url("mydisk.php?item=recycle"))
                        .header("Referer", recycleReferer())
                        .post(body)
                        .build()
                ).execute().body?.string().orEmpty()
                if (!respText.contains(successKeyword)) {
                    throw ApiError.Business(-1, "操作失败：${respText.take(80)}")
                }
            }
        }

    private suspend fun getRecyclePage(): String = withContext(Dispatchers.IO) {
        okHttp.newCall(
            Request.Builder()
                .url(url("mydisk.php?item=recycle&action=files"))
                .header("Referer", recycleReferer())
                .build()
        ).execute().body?.string().orEmpty()
    }

    /** #32 修复：回收站 Referer 用当前配置的管理域（旧实现硬编码 pc.woozooo.com，域名漂移后不一致） */
    private fun recycleReferer(): String =
        apiClient.domainInterceptor.snapshot().diskMain.trimEnd('/') + "/mydisk.php"
}
