package com.cloudbox.app.core.data.remote

import com.cloudbox.app.core.data.dto.AjaxmResponse
import com.cloudbox.app.core.data.dto.CommonResponse
import com.cloudbox.app.core.data.dto.DirListResponse
import com.cloudbox.app.core.data.dto.FileListResponse
import com.cloudbox.app.core.data.dto.FolderListResponse
import com.cloudbox.app.core.data.dto.ShareFileListResponse
import com.cloudbox.app.core.data.dto.ShareResponse
import com.cloudbox.app.core.data.dto.UploadResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * 蓝奏云 Web 接口（woozooo 体系）完整定义。
 *
 * 所有路径相对占位 baseUrl，实际域名由 LanzouDomainInterceptor 按角色重写：
 * - doupload.php / fileup.php → 管理域 diskMain
 * - filemoreajax.php / ajaxm.php → 分享域 shareBase
 *
 * task 编号依据：zaxtyson/LanZouCloud-API core.py（2025 年活跃维护版本，
 * commit 3bb917f），与需求规格的差异点已在方法注释标注。
 */
interface LanzouApiService {

    // ==================== 登录 ====================

    /**
     * 登录（需求规格指定方案）。
     * POST login.php，task=3&uid&pwd；成功以 Set-Cookie 下发 phpdisk_info+ylogin。
     * 注：LanZouCloud-API 源码显示官方已转向 mydisk.php+formhash 的登录方式，
     * login.php 为旧入口（部分账号仍可用）。失败时上层可降级到 cookie 导入。
     */
    @FormUrlEncoded
    @POST("login.php")
    suspend fun login(
        @Field("task") task: Int = 3,
        @Field("uid") uid: String,
        @Field("pwd") pwd: String
    ): Response<ResponseBody>

    // ==================== 文件列表 ====================

    /**
     * 网盘内文件列表（登录态，主方案）。
     * task=5&folder_id&pg —— 仅返回文件，不返回文件夹。
     * 与需求规格的差异：需求规格要求 lx/fid/uid/t/k/up/ls 参数，
     * 但 LanZouCloud-API 源码（2025）证实登录态列表只需 task/folder_id/pg，
     * t/k 仅用于"分享页"的 filemoreajax.php（见 getShareFileList）。
     * 已按"以更近期来源为准"原则采用 task=5 主方案。
     */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun getFileList(
        @Field("task") task: Int = 5,
        @Field("folder_id") folderId: Long,
        @Field("pg") pg: Int
    ): FileListResponse

    /**
     * 子文件夹列表（task=47，URL 带 uid）。
     * 响应兼容 text[{fol_id,name,onof}] 与 info[{folderid,name}] 两种形态。
     */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun getDirList(
        @Field("task") task: Int = 47,
        @Field("folder_id") folderId: Long,
        @Query("uid") uid: String? = null
    ): DirListResponse

    /**
     * 分享页文件夹内文件列表（filemoreajax.php，无需登录）。
     * 参数 lx/pg/k/t/fid/pwd —— t/k 必须从分享页 HTML 实时提取（HtmlExtractor），
     * 禁止缓存复用（需求规格 3 节 + 源码证实：t/k 每次从页面重新正则提取）。
     */
    @FormUrlEncoded
    @POST("filemoreajax.php")
    suspend fun getShareFileList(
        @Field("lx") lx: Int,
        @Field("pg") pg: Int,
        @Field("k") k: String,
        @Field("t") t: String,
        @Field("fid") fid: Long,
        @Field("pwd") pwd: String = ""
    ): ShareFileListResponse

    // ==================== 文件管理 ====================

    /** 新建文件夹 task=2：parent_id（根=-1）、folder_name、folder_description */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun createFolder(
        @Field("task") task: Int = 2,
        @Field("parent_id") parentId: Long,
        @Field("folder_name") folderName: String,
        @Field("folder_description") folderDescription: String = ""
    ): CommonResponse

    /** 重命名文件夹/改文件夹描述 task=4 */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun renameDir(
        @Field("task") task: Int = 4,
        @Field("folder_id") folderId: Long,
        @Field("folder_name") folderName: String,
        @Field("folder_description") folderDescription: String = ""
    ): CommonResponse

    /** 重命名文件 task=46（会员功能，无法改后缀） */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun renameFile(
        @Field("task") task: Int = 46,
        @Field("file_id") fileId: Long,
        @Field("file_name") fileName: String,
        @Field("type") type: Int = 2
    ): CommonResponse

    /** 移动文件 task=20：file_id、folder_id（目标，根=-1） */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun moveFile(
        @Field("task") task: Int = 20,
        @Field("file_id") fileId: Long,
        @Field("folder_id") folderId: Long
    ): CommonResponse

    /** 删除文件 task=6（入回收站） */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun deleteFile(
        @Field("task") task: Int = 6,
        @Field("file_id") fileId: Long
    ): CommonResponse

    /** 删除文件夹 task=3（入回收站） */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun deleteDir(
        @Field("task") task: Int = 3,
        @Field("folder_id") folderId: Long
    ): CommonResponse

    /** 设置文件提取码 task=23：shows(0关/1开)、shownames(密码 2-6 位) */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun setFilePasswd(
        @Field("task") task: Int = 23,
        @Field("file_id") fileId: Long,
        @Field("shows") shows: Int,
        @Field("shownames") shownames: String
    ): CommonResponse

    /** 设置文件夹提取码 task=16（0-12 位；注意：非会员现在不允许关闭提取码） */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun setDirPasswd(
        @Field("task") task: Int = 16,
        @Field("folder_id") folderId: Long,
        @Field("shows") shows: Int,
        @Field("shownames") shownames: String
    ): CommonResponse

    /** 设置文件描述 task=11（⚠️ 一旦设置后不能置空） */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun setFileDesc(
        @Field("task") task: Int = 11,
        @Field("file_id") fileId: Long,
        @Field("desc") desc: String
    ): CommonResponse

    /** 获取全部文件夹列表 task=19（移动选择目标用），file_id 传 -1 */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun getAllFolders(
        @Field("task") task: Int = 19,
        @Field("file_id") fileId: Long = -1
    ): FolderListResponse

    // ==================== 分享 ====================

    /** 获取文件分享信息 task=22：info{f_id, is_newd, pwd, onof, name}，
     *  分享链接 = is_newd + '/' + f_id（如 https://wwi.lanzoup.com/iXXXXX） */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun getFileShareInfo(
        @Field("task") task: Int = 22,
        @Field("file_id") fileId: Long
    ): ShareResponse

    /** 获取文件夹分享信息 task=18：info{new_url, name, pwd, onof} */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun getDirShareInfo(
        @Field("task") task: Int = 18,
        @Field("folder_id") folderId: Long
    ): ShareResponse

    /** 获取文件附加信息 task=12：{text:无后缀文件名, info:描述} */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun getFileInfo(
        @Field("task") task: Int = 12,
        @Field("file_id") fileId: Long
    ): Response<ResponseBody>

    // ==================== 上传 ====================

    /**
     * 上传文件（fileup.php，multipart）。
     * 参数与需求规格差异：源码（2025）实际参数名是 folder_id_bb_n 而非 folder_id，
     * 且必带 vie=2/ve=2/id=WU_FILE_0/name。按源码实现。
     */
    @Multipart
    @POST("fileup.php")
    suspend fun upload(
        @Part("task") task: RequestBody,
        @Part("vie") vie: RequestBody,
        @Part("ve") ve: RequestBody,
        @Part("id") id: RequestBody,
        @Part("folder_id_bb_n") folderId: RequestBody,
        @Part("name") name: RequestBody,
        @Part file: MultipartBody.Part
    ): UploadResponse

    // ==================== 直链解析 ====================

    /**
     * 直链解析（ajaxm.php）。
     * 需求规格：action=downprocess, sign, file_id, p, kd=1。
     * 源码差异：sign 来源分两种（有/无提取码分支），直链拼接为 dom + '/file/' + url
     * 后 GET 一次拿 Location 才是真直链（本仓库用 @Streaming 防止 Gson 尝试解析下载页）。
     * kd 参数源码未使用，保留需求规格的 kd=1。
     */
    @FormUrlEncoded
    @POST("ajaxm.php")
    suspend fun downProcess(
        @Field("action") action: String = "downprocess",
        @Field("sign") sign: String,
        @Field("file_id") fileId: String? = null,
        @Field("p") pwd: String = "",
        @Field("kd") kd: Int = 1,
        @Field("ves") ves: Int? = null
    ): AjaxmResponse

    /** 通用 GET（分享页/iframe 页/重定向探测），不经过 Retrofit 转换器 */
    @Streaming
    @GET
    suspend fun get(@Url url: String): Response<ResponseBody>
}
