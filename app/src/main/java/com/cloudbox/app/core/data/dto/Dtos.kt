package com.cloudbox.app.core.data.dto

import com.google.gson.annotations.SerializedName

/**
 * task=5 文件列表响应：{"info": 1, "text": [{id, name_all, time, size, downs, onof, is_des}, ...]}
 * info=0 表示已取完（翻页终止条件）。
 * （来源：LanZouCloud-API core.py，2025 年仍有效的接口）
 */
data class FileListResponse(
    @SerializedName("info") val info: Int,
    @SerializedName("text") val text: List<RemoteFile>? = null
)

data class RemoteFile(
    @SerializedName("id") val id: Long,
    @SerializedName("name_all") val nameAll: String,
    @SerializedName("time") val time: String?,
    @SerializedName("size") val size: String?,
    @SerializedName("downs") val downs: String?,
    @SerializedName("onof") val onof: String?,
    @SerializedName("is_des") val isDes: String?
)

/**
 * task=47 子文件夹列表响应（doupload.php?uid=xxx）：text[{fol_id,name,onof,folder_des}]。
 * info 为接口元信息字段（非文件夹列表，V5 修复：不再映射成文件夹，防幽灵条目）
 */
data class DirListResponse(
    @SerializedName("text") val text: List<RemoteDir>? = null,
    @SerializedName("info") val info: List<RemoteDirAlt>? = null,
    @SerializedName("zt") val zt: Int? = null
)

data class RemoteDir(
    @SerializedName("fol_id") val folId: Long,
    @SerializedName("name") val name: String,
    @SerializedName("onof") val onof: String? = null,
    @SerializedName("folder_des") val folderDes: String? = null
)

data class RemoteDirAlt(
    @SerializedName("folderid") val folderId: Long,
    @SerializedName("name") val name: String
)

/** task=19 全部文件夹列表（移动选择目标用）：{zt:1, info:[{folder_id, folder_name}]} */
data class FolderListResponse(
    @SerializedName("zt") val zt: Int,
    @SerializedName("info") val info: List<RemoteFolder>? = null
)

data class RemoteFolder(
    @SerializedName("folder_id") val folderId: Long,
    @SerializedName("folder_name") val folderName: String
)

/** 通用操作响应：{"zt": 1} 成功 */
data class CommonResponse(
    @SerializedName("zt") val zt: Int
)

/** 分享信息响应（task=22 文件 / task=18 文件夹） */
data class ShareResponse(
    @SerializedName("info") val info: ShareInfoDto? = null,
    @SerializedName("zt") val zt: Int? = null
)

data class ShareInfoDto(
    // 文件分享（task=22）
    @SerializedName("f_id") val fId: String? = null,
    @SerializedName("is_newd") val isNewd: String? = null,
    // 文件夹分享（task=18）
    @SerializedName("new_url") val newUrl: String? = null,
    // 公共
    @SerializedName("name") val name: String? = null,
    @SerializedName("pwd") val pwd: String? = null,
    @SerializedName("onof") val onof: String? = null
)

/** 上传响应：{"zt":1, "text":{"id": 文件ID}} */
data class UploadResponse(
    @SerializedName("zt") val zt: Int,
    @SerializedName("text") val text: UploadTextDto? = null,
    @SerializedName("info") val info: String? = null
)

data class UploadTextDto(
    @SerializedName("id") val id: String? = null
)

/** 直链解析响应（ajaxm.php）：{"zt":1, "dom":域名, "url":路径, "inf":文件名} */
data class AjaxmResponse(
    @SerializedName("zt") val zt: Int,
    @SerializedName("dom") val dom: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("inf") val inf: String? = null
)

/** 分享页文件夹内文件列表（filemoreajax.php）：{"zt":1, "text":[{name_all,time,size,id}]} */
data class ShareFileListResponse(
    @SerializedName("zt") val zt: Int,
    @SerializedName("text") val text: List<RemoteFile>? = null
)
