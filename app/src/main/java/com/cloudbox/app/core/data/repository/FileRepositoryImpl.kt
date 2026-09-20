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
import org.json.JSONObject
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

    // ==================== 回收站页面解析（原版 recycle.lua 实证） ====================

    /** 文件条目 id：恢复链接里的 file_id */
    private val RE_RECYCLE_FILE_ID = Regex("""action=file_restore&file_id=(\d+)""")

    /** 文件条目名：<img … border='0' /> 文件名</a> */
    private val RE_RECYCLE_FILE_NAME = Regex("""border='0'\s*/>\s*([^<]+)</a>""")

    /** 文件夹条目 id：`show_files&folder_id=` 或 `folder_restore&folder_id=`（两种链接都可能出现） */
    private val RE_RECYCLE_FOLDER_ID =
        Regex("""action=(?:show_files|folder_restore)&folder_id=(\d+)""")

    /** 文件夹条目名：<img … />&nbsp;文件夹名</a> */
    private val RE_RECYCLE_FOLDER_NAME = Regex("""/>&nbsp;([^<]+)</a>""")

    /**
     * 回收站「查看文件夹内文件」条目名。
     *
     * 正则来自原版 `recycle.lua` 的 `查看文件夹弹窗`：
     * 原版用 `a2:gmatch("/>&nbsp;(.-) <font color=")` —— 注意分隔符是
     * `<font color=`（后面紧跟大小信息），而不是回收站首页那个 `</a>`。
     * 两个页面 HTML 结构不同，正则不能混用。
     */
    private val RE_RECYCLE_FOLDER_ITEM_NAME = Regex("""/>&nbsp;(.+?)\s*<font color=""")

    /** 条目大小：`#CCCCCC">(大小)<`（原版 recycle.lua:556 同款） */
    private val RE_RECYCLE_FOLDER_ITEM_SIZE = Regex("""#CCCCCC">\(([^)]*)\)<""")

    /** 构造走统一拦截器的绝对 URL */
    private fun url(pathAndQuery: String) = "https://${AppConstants.PLACEHOLDER_HOST}/$pathAndQuery"

    override suspend fun getPage(folderId: Long, page: Int): Result<FileListPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uid = accountStore.currentUid() ?: throw ApiError.CookieExpired("未登录")
                // 1) 子文件夹（task=47；URL 上的 ?uid= 由 LanzouUidInterceptor 注入）
                // V5 修复：只解析 text。旧代码额外把响应的 info 映射成文件夹（"兼容两种形态"），
                // 但参考实现（LanZouCloud-API get_dir_list）只读 text——info 实为接口的
                // 元信息字段，映射成文件夹会注入服务端并不存在的"幽灵文件夹"
                // （用户实测：进入二级目录后一级目录名仍出现在列表里，实际并无该文件夹）。
                val dirsResp = api.getDirList(folderId = folderId)
                // 用 .dirs / .items 访问器而不是 .text：服务端在空目录/最后一页会
                // 把数组字段换成字符串 "no file"（见 Dtos.kt 顶部说明）
                val folders = dirsResp.dirs.map {
                    CloudFile(it.folId, it.name, true, null, null, it.onof, it.folderDes, folderId)
                }

                // 2) 文件（task=5，pg 翻页）
                val filesResp = api.getFileList(folderId = folderId, pg = page)
                val files = filesResp.items.map {
                    CloudFile(it.id, it.nameAll, false, it.size, it.time, it.onof, it.isDes, folderId)
                }
                val hasMore = filesResp.hasMore

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
                api.getAllFolders().folders.map { it.folderId to it.folderName }
            }
        }

    override suspend fun createFolder(parentId: Long, name: String): Result<Long?> =
        withContext(Dispatchers.IO) {
            runCatching {
                // #7 修复：before 快照必须在 create 调用【之前】取（旧实现顺序写反，
                // 两次 getAllFolders 结果相同，diff 恒空 → 新文件夹 id 永远返回 null）
                val before = api.getAllFolders().folders.map { it.folderId to it.folderName }
                val resp = api.createFolder(parentId = parentId, folderName = name)
                if (resp.zt != 1) throw ApiError.Business(resp.zt, "新建文件夹失败")
                // 优先用服务端在 text 里回传的新文件夹 id —— 原版「蓝云」
                // home_func.lua「新建文件夹」就是直接用响应 text 作 id。
                // 比"两次全量求差集"可靠：差集遇同名文件夹会取错，且要多打两次 getAllFolders。
                resp.textId?.let { return@runCatching it }
                // 兜底：服务端未回传 text 时，退回前后文件夹列表差异定位
                // （before 快照已在 create 调用之前取好，见上文 #7 修复）
                val after = api.getAllFolders().folders.map { it.folderId to it.folderName }
                after.filter { it.second == name && it !in before }.firstOrNull()?.first
            }
        }

    override suspend fun rename(file: CloudFile, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = if (file.isFolder) {
                    // ⚠️ task=4 是**整体覆盖**：只传新名字会把文件夹「简介」清空
                    // （旧实现的静默数据损毁 bug，代码自己的注释也承认 task=4 是覆盖式）。
                    // 先读回原描述，再和新名字一起提交 —— 与原版「蓝云」
                    // home_file.lua「文件夹修改资料」一致（name 与 des 同时提交）。
                    val desc = getDirDesc(file.id).getOrDefault("")
                    api.renameDir(folderId = file.id, folderName = newName, folderDescription = desc)
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

    /**
     * 设置文件夹提取码（task=16）。
     *
     * 与文件走不同 task（文件是 23）。失败时优先透传服务端 info 文案 ——
     * 非会员会收到"此功能仅会員使用（个人中心 - 会员个性化）"，
     * 这句比笼统的"设置失败"有用得多。
     */
    override suspend fun setDirPasswd(folderId: Long, pwd: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.setDirPasswd(
                    folderId = folderId,
                    shows = if (pwd.isBlank()) 0 else 1,
                    shownames = pwd
                )
                // zt 可空（非会员返回 zt:null），用 isOk 判定；失败时透传服务端文案
                if (!resp.isOk) {
                    throw ApiError.Business(
                        resp.zt ?: -1,
                        resp.infoText ?: "设置文件夹提取码失败"
                    )
                }
            }
        }

    /**
     * 设置文件夹描述（task=4）。
     *
     * ⚠️ task=4 是整体覆盖：不传 folder_name 会把文件夹名清空，所以必须把原 name 带上。
     * 官网 `fol_desgo` 正是把 name 与 des 一起提交的。
     */
    override suspend fun setDirDesc(folder: CloudFile, desc: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.renameDir(
                    folderId = folder.id,
                    folderName = folder.name,   // 必须回填原名，否则会被清空
                    folderDescription = desc
                )
                if (resp.zt != 1) throw ApiError.Business(resp.zt, "设置文件夹描述失败")
            }
        }

    override suspend fun getDirDesc(folderId: Long): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                api.getDirShareInfo(folderId = folderId).info?.des ?: ""
            }
        }

    override suspend fun setFileDesc(fileId: Long, desc: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.setFileDesc(fileId = fileId, desc = desc)
                if (resp.zt != 1) throw ApiError.Business(resp.zt, "设置描述失败")
            }
        }

    /**
     * 读取文件描述（task=12）。
     *
     * 接口声明成 Response<ResponseBody>（原样返回），这里手动解析：
     *   {"zt":1,"info":"描述内容","text":"无后缀文件名"}
     * 从未设置过描述时 info 缺失或为 null，统一归 ""，让 UI 显示空输入框。
     *
     * 失败不抛异常而是返回 ""（runCatching + getOrDefault）：
     * 读描述只是"打开弹窗时回填"，读失败不该阻断用户改描述，
     * 给个空框让他能写就行 —— 比弹一句"读取失败"更有用。
     */
    override suspend fun getFileDesc(fileId: Long): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Retrofit 的 body 是方法 body()，不是属性（项目其他处用 execute().body?.string()）
                val body = api.getFileInfo(fileId = fileId).body()?.string().orEmpty()
                val json = JSONObject(body)
                if (json.optInt("zt", 0) != 1) return@runCatching ""
                val info = json.opt("info")
                // info 可能是字符串、null，或（无描述时）数组/数字，统一转字符串
                when (info) {
                    null -> ""
                    is String -> info
                    else -> info.toString()
                }
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

    /**
     * 回收站列表。
     *
     * 解析依据（原版 recycle.lua 反编译确认，非猜测）：
     *   文件条目：  id ← `action=file_restore&file_id=(\d+)`
     *              名 ← `border='0' />(.+?)</a>`
     *   文件夹条目：id ← `action=show_files&folder_id=(\d+)`（退回 folder_restore&folder_id=）
     *              名 ← `/>&nbsp;(.+?)</a>`
     * 旧实现用 Jsoup 找 `input[value=$id]` 的父节点取 <a> 文本——页面里根本没有
     * 这种 input，结果恒为占位名（"文件12345"），是"回收站看不出是什么东西"的原因。
     * id 与名字分别成表后按下标配对；数量对不上时用占位名兜底（不整块失败）。
     */
    override suspend fun getRecycleItems(): Result<RecycleItems> = withContext(Dispatchers.IO) {
        runCatching {
            val html = getRecyclePage()

            val fileIds = RE_RECYCLE_FILE_ID.findAll(html)
                .map { it.groupValues[1].toLong() }.distinct().toList()
            val fileNames = RE_RECYCLE_FILE_NAME.findAll(html)
                .map { it.groupValues[1].trim() }.toList()

            val folderIds = RE_RECYCLE_FOLDER_ID.findAll(html)
                .mapNotNull { it.groupValues[1].toLongOrNull() }
                .distinct().toList()
            val folderNames = RE_RECYCLE_FOLDER_NAME.findAll(html)
                .map { it.groupValues[1].trim() }.toList()

            val files = fileIds.mapIndexed { i, id ->
                CloudFile(id, fileNames.getOrNull(i) ?: "文件$id", false, null, null, null, null, -1)
            }
            val folders = folderIds.mapIndexed { i, id ->
                CloudFile(id, folderNames.getOrNull(i) ?: "文件夹$id", true, null, null, null, null, -1)
            }
            RecycleItems(files, folders)
        }
    }

    /**
     * 查看回收站内某文件夹下的文件（只读，不改动任何东西）。
     *
     * 页面：`mydisk.php?item=recycle&action=show_files&folder_id=<id>`
     * 解析正则来自原版 recycle.lua（见 [RE_RECYCLE_FOLDER_ITEM_NAME] 注释）。
     *
     * 名字与大小分别成表再按下标配对：两段正则各自匹配的数量可能不等
     * （名字一定每个条目都有，大小在文件夹里可能没有），
     * 按下标配对 + 缺失兜底，好过整块失败。
     */
    override suspend fun getRecycleFolderItems(folderId: Long): Result<List<CloudFile>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = okHttp.newCall(
                    Request.Builder()
                        .url(url("mydisk.php?item=recycle&action=show_files&folder_id=$folderId"))
                        .header("Referer", recycleReferer())
                        .build()
                ).execute().body?.string().orEmpty()

                val names = RE_RECYCLE_FOLDER_ITEM_NAME.findAll(html)
                    .map { it.groupValues[1].trim() }
                    .filter { it.isNotBlank() }
                    .toList()
                val sizes = RE_RECYCLE_FOLDER_ITEM_SIZE.findAll(html)
                    .map { it.groupValues[1].trim() }
                    .toList()

                names.mapIndexed { i, name ->
                    CloudFile(
                        id = -(i + 1).toLong(), // 只读展示，不做操作，id 无实际用途
                        name = name,
                        isFolder = false,
                        size = sizes.getOrNull(i),
                        time = null,
                        onof = null,
                        isDes = null,
                        parentId = folderId
                    )
                }
            }
        }

    override suspend fun restoreItems(fileIds: List<Long>, folderIds: List<Long>): Result<Unit> =        recycleAction(
            actionOf = { id, isFolder ->
                if (isFolder) "folder_restore" to "folder_id=$id" else "file_restore" to "file_id=$id"
            },
            ids = fileIds.map { it to false } + folderIds.map { it to true }
        )

    override suspend fun deleteCompleteItems(fileIds: List<Long>, folderIds: List<Long>): Result<Unit> =
        recycleAction(
            actionOf = { id, isFolder ->
                if (isFolder) "folder_delete_complete" to "folder_id=$id" else "file_delete_complete" to "file_id=$id"
            },
            ids = fileIds.map { it to false } + folderIds.map { it to true }
        )

    override suspend fun restoreAll(): Result<Unit> = recycleBulk("restore_all")

    override suspend fun clearRecycle(): Result<Unit> = recycleBulk("delete_all")

    /**
     * 单项操作：GET 取 formhash + ref → POST 执行（#12：逐项加 1-3s 延时防风控）。
     *
     * ⚠️ `ref` 字段是原版 recycle.lua 实测必带的（页面隐藏域 `name="ref" value="…"`，
     * 原版取值正则：`name="ref" value="(.-)"`）。旧 Kotlin 实现没传 ref，
     * 服务端会拒绝操作 —— 这是"回收站点了没反应"的成因。
     */
    private suspend fun recycleAction(
        actionOf: (Long, Boolean) -> Pair<String, String>,
        ids: List<Pair<Long, Boolean>>
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
                // ref：页面隐藏域；缺了用回收站列表 URL 兜底（原版直接用页面里的值）
                val ref = Regex("""name="ref"\s+value="([^"]*)"""").find(getHtml)
                    ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                    ?: "${recycleReferer()}?item=recycle&action=files"
                val body = FormBody.Builder()
                    .add("action", action)
                    .add("task", action)
                    .add(if (isFolder) "folder_id" else "file_id", id.toString())
                    .add("ref", ref)
                    .add("formhash", formhash)
                    .build()
                postRecycle(body)
            }
        }
    }

    /** 批量操作（restore_all / delete_all） */
    private suspend fun recycleBulk(action: String): Result<Unit> =
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
                postRecycle(body)
            }
        }

    /**
     * 提交回收站表单并判定成败。
     *
     * 判定标准（对齐原版 recycle.lua）：HTTP 2xx 即成功 —— 原版回调里只判
     * `a1 == 200 and a2`。旧 Kotlin 实现改成"响应体必须包含'恢复成功'/'删除成功'"，
     * 而服务端实际并不回这两个词，于是**每次操作都被误判为失败**。
     *
     * 但也不能无条件认成功：未登录时服务端会回一个 200 的登录页。
     * 因此额外排除"被踢回登录页"这一种情形，避免重蹈"假成功/假失败"两个极端。
     */
    private fun postRecycle(body: FormBody) {
        val resp = okHttp.newCall(
            Request.Builder()
                .url(url("mydisk.php?item=recycle"))
                .header("Referer", recycleReferer())
                .post(body)
                .build()
        ).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw ApiError.Business(resp.code, "回收站操作失败：HTTP ${resp.code}")
        }
        if (text.contains("用户登录") || text.contains("action=login") || text.contains("login not")) {
            throw ApiError.CookieExpired("回收站操作被重定向到登录页")
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
