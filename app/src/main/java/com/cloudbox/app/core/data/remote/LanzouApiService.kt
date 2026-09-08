package com.cloudbox.app.core.data.remote

import com.cloudbox.app.core.data.dto.AjaxFileResponse
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
    // V4（2026-08-31）：login.php（task=3）已在 pc/up.woozooo.com 双双实测 404，端点移除。
    // 登录不走 Retrofit——统一账号中心 accounts.woozooo.com 为固定真实域名，
    // 协议含 acw 挑战/中转跳转，实现见 AuthRepositoryImpl（账号中心协议 helpers）。

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
     * 子文件夹列表（task=47）。
     * 响应兼容 text[{fol_id,name,onof}] 与 info[{folderid,name}] 两种形态。
     *
     * URL 上的 `?uid=<数字 uid>` 由 [LanzouUidInterceptor] 统一注入（原版 App 所有
     * doupload.php 请求都带它，见 home_func.lua:2463 的 uid后缀）。
     * 早前版本曾在此处传"登录账号名"——那是错的（原版传的是网盘数字 uid），
     * 服务端解析失败会导致子文件夹列表为空。
     */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun getDirList(
        @Field("task") task: Int = 47,
        @Field("folder_id") folderId: Long
    ): DirListResponse

    /**
     * 分享页文件夹内文件列表（filemoreajax.php，无需登录）。
     *
     * 2026-09 实测校准：新版接口在 URL 上带 `?file=<fid>` 查询，且表单必须携带
     * uid / puid / rep / up 四个字段（页面 JS 实证，缺任一都可能被判为非法请求）。
     * t / k / fid / uid / puid 全部从分享页 HTML **实时**提取（HtmlExtractor），
     * 禁止缓存复用——这些值是每次页面渲染时动态下发的。
     */
    @FormUrlEncoded
    @POST("filemoreajax.php")
    suspend fun getShareFileList(
        @Query("file") fileFid: String,
        @Field("lx") lx: Int = 2,
        @Field("fid") fid: String,
        @Field("uid") uid: String = "",
        @Field("puid") puid: String = "",
        @Field("pg") pg: Int,
        @Field("rep") rep: String = "0",
        @Field("t") t: String,
        @Field("k") k: String,
        @Field("up") up: Int = 1,
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
     * 上传文件（html5up.php，multipart）。
     *
     * V6 协议迁移（2026-09 实测）：旧 fileup.php 已下线（pc/up.woozooo.com 均 404），
     * 网页端现走 html5up.php。
     *
     * 字段说明与坑位：
     * - folder_id 与 folder_id_bb_n **同时发送**：公开实现里两种写法都存在
     *   （AList 用 folder_id_bb_n，多份上传脚本用 folder_id），服务端只认其中一个，
     *   双写可在不同站点版本下都命中，多传一个字段无害。
     * - type / lastModifiedDate 为浏览器 File 对象元数据，服务端不严格校验但需携带。
     * - Referer 由 [LanzouRefererInterceptor] 自动补全为网盘文件页——缺失 Referer 时
     *   接口会返回 zt=1 却不入库，是"假成功"的成因之一。
     *
     * 响应：成功 {"zt":1,"text":[{id,name,time,size,icon,downs}]}（text 为数组）；
     *       未登录 {"zt":9,"info":"login not","text":"error"}（text 为字符串）。
     */
    @Multipart
    @POST("html5up.php")
    suspend fun upload(
        @Part("task") task: RequestBody,
        @Part("vie") vie: RequestBody,
        @Part("ve") ve: RequestBody,
        @Part("id") id: RequestBody,
        @Part("folder_id_bb_n") folderIdBbN: RequestBody,
        @Part("folder_id") folderId: RequestBody,
        @Part("name") name: RequestBody,
        @Part("type") type: RequestBody,
        @Part("lastModifiedDate") lastModifiedDate: RequestBody,
        @Part file: MultipartBody.Part
    ): UploadResponse

    // ==================== 账号中心设置（原版 account.lua） ====================

    /**
     * 个人分享链访问码 task=7。
     *
     * 原版 account.lua:2104 调用：`个人分享链("0", 个人文字.text)` →
     * `task=7&codeoff=0&code=<内容>`。
     * codeoff：0=启用访问码 / 1=不需要访问码（与文件提取码的 shows 语义相反，
     * **别**照抄 task=23 的 shows 写法）。
     */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun setPersonalLinkCode(
        @Field("task") task: Int = 7,
        @Field("codeoff") codeoff: Int,
        @Field("code") code: String
    ): CommonResponse

    /**
     * 修改密码 task=8。
     *
     * 原版 account.lua:1116：`task=8&new_pwd=<新>&old_pwd=<旧>`，
     * 两边都是**明文**（与登录同源，服务端自己处理）。
     */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun changePassword(
        @Field("task") task: Int = 8,
        @Field("old_pwd") oldPwd: String,
        @Field("new_pwd") newPwd: String
    ): CommonResponse

    /**
     * 外链（个人主页）标题与简介 task=10。
     *
     * 原版 account.lua:1517 调用：`外链设置(标题文字.text, 简介文字.text)` →
     * `task=10&ubt=<标题>&usm=<简介>`。
     * 字段名很反直觉：ubt=标题(url bt)，usm=简介(url summary)。
     */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun setExternalLink(
        @Field("task") task: Int = 10,
        @Field("ubt") ubt: String,
        @Field("usm") usm: String
    ): CommonResponse

    /**
     * 是否显示发布者 task=15。
     *
     * 原版 account.lua:1794 调用：`显示发布者("0", 显示文字.text)` →
     * `task=15&shows=0&shownames=<昵称>`。
     * 注意这里 shows/shownames 复用的是"提取码"那一对字段名，但语义完全不同：
     * shows=显示开关(0/1)，shownames=展示的发布者昵称。
     */
    @FormUrlEncoded
    @POST("doupload.php")
    suspend fun setPublisher(
        @Field("task") task: Int = 15,
        @Field("shows") shows: Int,
        @Field("shownames") shownames: String
    ): CommonResponse

    // ==================== 直链解析 ====================

    /**
     * 直链解析（现行端点 ajaxfile.php，2026-09 实测可用）。
     *
     * 旧端点 ajaxm.php + 参数 {action, sign, file_id, p, kd, ves} 已随页面改版废弃：
     * 新版单文件页把签名藏在 iframe（/fn?…）的 `var wp_sign` 里，fid 用 URL 查询传递，
     * 并新增 websignkey / signs / websign 三个校验字段。继续打 ajaxm.php 必然拿不到直链。
     *
     * 实测请求：POST /ajaxfile.php?file=96810913
     *   action=downprocess&websignkey=asXy&signs=asXy&sign=<wp_sign>&websign=&kd=1&ves=1
     * 成功响应：{"zt":1,"dom":"https://developer2.lanrar.com","url":"?A2VUags6…","inf":0}
     * 直链拼接：dom + "/file/" + url
     */
    @FormUrlEncoded
    @POST("ajaxfile.php")
    suspend fun downProcess(
        @Query("file") fileId: String,
        @Field("action") action: String = "downprocess",
        @Field("websignkey") websignkey: String = "",
        @Field("signs") signs: String = "",
        @Field("sign") sign: String,
        @Field("websign") websign: String = "",
        @Field("kd") kd: Int = 1,
        @Field("ves") ves: Int = 1,
        @Field("p") pwd: String = ""
    ): AjaxFileResponse

    /** 通用 GET（分享页/iframe 页/重定向探测），不经过 Retrofit 转换器 */
    @Streaming
    @GET
    suspend fun get(@Url url: String): Response<ResponseBody>
}
