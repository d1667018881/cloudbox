package com.cloudbox.app.core.data.dto

import com.google.gson.annotations.SerializedName

// ============================================================================
// ⚠️ 为什么所有"数组字段"都用 Any? 而不是 List<T>
// ============================================================================
// 蓝奏云在"无数据"时不会返回空数组，而是把数组字段换成字符串。实测样本：
//   filemoreajax.php 翻到最后一页：{"zt":2,"info":"没有了","text":"no file"}
//   doupload.php task=5 空目录：   {"zt":2,"info":0,"text":"no file"}
// 若字段声明为 List<T>，Gson 在**反序列化阶段**就抛 JsonSyntaxException，
// 调用方连 zt 都看不到 —— 表现为"翻页必崩""往空文件夹上传后云端确认失败"。
//
// 对策：字段统一用 Any? 承接，再提供带类型的访问器（items/dirs/folders）。
// 访问器对非数组形态一律返回空列表，对 id 之类的字段同时容忍数字与字符串
// （实测分享列表的 id 是字符串 "i1evj0klyr0d"，网盘列表的 id 是数字）。
// 这样服务端再换返回形态也不会让 App 崩，只会出现空列表 —— 可诊断、可恢复。
// ============================================================================

/** 把 Gson 解析出的 Any? 统一规整为 List<Map<String, Any?>>；非数组一律空列表 */
private fun Any?.asMapList(): List<Map<String, Any?>> =
    (this as? List<*>)?.mapNotNull { it as? Map<*, *> }
        ?.map { m -> m.entries.associate { (k, v) -> k.toString() to v } }
        .orEmpty()

/** 从 Map 里取 Long：兼容数字与数字字符串 */
private fun Map<String, Any?>.long(key: String): Long =
    (this[key] as? Number)?.toLong() ?: this[key]?.toString()?.toLongOrNull() ?: 0L

/** 从 Map 里取 String */
private fun Map<String, Any?>.str(key: String): String? = this[key]?.toString()

/**
 * task=5 文件列表响应：{"info": 1, "text": [{id, name_all, time, size, downs, onof, is_des}, ...]}
 * info=0 表示已取完（翻页终止条件）。
 */
data class FileListResponse(
    @SerializedName("info") val info: Any? = null,
    @SerializedName("text") val text: Any? = null,
    @SerializedName("zt") val zt: Int? = null
) {
    /** 文件条目（text 为 "no file" 等字符串时返回空列表） */
    val items: List<RemoteFile>
        get() = text.asMapList().map {
            RemoteFile(
                id = it.long("id"),
                nameAll = it.str("name_all").orEmpty(),
                time = it.str("time"),
                size = it.str("size"),
                downs = it.str("downs"),
                onof = it.str("onof"),
                isDes = it.str("is_des")
            )
        }

    /** 是否还有下一页：只有 info 是数字且 != 0 才算 true（字符串形态一律 false，防死循环） */
    val hasMore: Boolean get() = (info as? Number)?.toInt()?.let { it != 0 } ?: false
}

data class RemoteFile(
    val id: Long,
    val nameAll: String,
    val time: String?,
    val size: String?,
    val downs: String?,
    val onof: String?,
    val isDes: String?
)

/**
 * task=47 子文件夹列表响应（doupload.php?uid=xxx）：text[{fol_id,name,onof,folder_des}]。
 * info 为接口元信息字段（非文件夹列表，V5 修复：不再映射成文件夹，防幽灵条目）
 */
data class DirListResponse(
    @SerializedName("text") val text: Any? = null,
    @SerializedName("info") val info: Any? = null,
    @SerializedName("zt") val zt: Int? = null
) {
    /** 子文件夹条目 */
    val dirs: List<RemoteDir>
        get() = text.asMapList().map {
            RemoteDir(
                folId = it.long("fol_id"),
                name = it.str("name").orEmpty(),
                onof = it.str("onof"),
                folderDes = it.str("folder_des")
            )
        }
}

data class RemoteDir(
    val folId: Long,
    val name: String,
    val onof: String? = null,
    val folderDes: String? = null
)

/** task=19 全部文件夹列表（移动选择目标用）：{zt:1, info:[{folder_id, folder_name}]} */
data class FolderListResponse(
    @SerializedName("zt") val zt: Int,
    @SerializedName("info") val info: Any? = null
) {
    /** 文件夹条目（info 为字符串时返回空列表） */
    val folders: List<RemoteFolder>
        get() = info.asMapList().map {
            RemoteFolder(folderId = it.long("folder_id"), folderName = it.str("folder_name").orEmpty())
        }
}

data class RemoteFolder(
    val folderId: Long,
    val folderName: String
)

/** 通用操作响应：{"zt": 1} 成功 */
data class CommonResponse(
    @SerializedName("zt") val zt: Int,
    /**
     * 服务端提示文案（成功时如 "设置成功"，失败时如 ""）。
     * 用 Any? 而非 String?：实测部分接口失败时 info 是数字 0 或数组，
     * 声明成 String 会让 Gson 抛 JsonSyntaxException，反而盖掉 zt 判定。
     */
    @SerializedName("info") val info: Any? = null
) {
    /** info 转字符串；非字符串形态一律返回 null */
    val infoText: String? get() = (info as? String)?.takeIf { it.isNotBlank() }
}

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

/** 上传响应。
 *  成功：{"zt":1, "text":[{id, name, time, size, icon, downs}]}（text 为对象数组）
 *  未登录：{"zt":9, "info":"login not", "text":"error"}（text 为字符串）
 *  旧 fileup.php 时代 text 为单对象；html5up.php（V6）为数组。用 Any? 兼容两种形态。 */
data class UploadResponse(
    @SerializedName("zt") val zt: Int,
    @SerializedName("text") val text: Any? = null,
    @SerializedName("info") val info: String? = null
)

data class UploadTextDto(
    @SerializedName("id") val id: String? = null
)

/** 直链解析响应（旧端点 ajaxm.php，已下线，保留兼容）：
 *  {"zt":1, "dom":域名, "url":路径, "inf":文件名} */
data class AjaxmResponse(
    @SerializedName("zt") val zt: Int,
    @SerializedName("dom") val dom: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("inf") val inf: String? = null
)

/**
 * 直链解析响应（现行端点 ajaxfile.php，2026-09 实测）。
 * 实测样本：{"zt":1,"dom":"https:\/\/developer2.lanrar.com","url":"?A2VUags6…","inf":0}
 *
 * 与旧文档两处不同：
 * 1) url 以 '?' 开头（不再是 '/xxx.html'），拼接规则仍是 dom + "/file/" + url；
 * 2) inf 正常时是**数字 0** 而不是文件名 —— 用 Any? 承接避免 Gson 类型不符直接抛异常，
 *    真实文件名改从分享页 <title> 提取（见 DirectLinkRepositoryImpl）。
 */
data class AjaxFileResponse(
    @SerializedName("zt") val zt: Int,
    @SerializedName("dom") val dom: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("inf") val inf: Any? = null
) {
    /** inf 为字符串且非空、非纯数字时才算有效文件名 */
    val infAsName: String?
        get() = (inf as? String)?.takeIf { it.isNotBlank() && it.toLongOrNull() == null }
}

/**
 * 分享页文件夹内文件列表（filemoreajax.php）。
 *
 * 实测三种形态：
 *   有数据：  {"zt":1,"info":"sucess","text":[{…}]}
 *   最后一页：{"zt":2,"info":"没有了","text":"no file"}   ← text 是**字符串**
 *   提取码错：{"zt":3,"info":"密码不正确"}
 * 因此 zt 也要用 Int?（个别异常响应没有 zt），text 用 Any?（见文件顶部说明）。
 */
data class ShareFileListResponse(
    @SerializedName("zt") val zt: Int? = null,
    @SerializedName("text") val text: Any? = null,
    @SerializedName("info") val info: Any? = null
) {
    /** 文件条目（"no file" / 空 / 非数组 → 空列表） */
    val items: List<ShareFileItem>
        get() = text.asMapList().map {
            ShareFileItem(
                id = it.str("id"),
                nameAll = it.str("name_all"),
                size = it.str("size"),
                time = it.str("time"),
                icon = it.str("icon"),
                duan = it.str("duan")
            )
        }

    /** 是否还有下一页：zt==1 才有（zt==2 取完、zt==3 提取码错误） */
    val hasMore: Boolean get() = zt == 1
}

/**
 * 分享页文件条目。
 *
 * id 必须是 String：实测 filemoreajax 返回 {"id":"i1evj0klyr0d"}（字母+数字的字符串 id），
 * 旧实现复用网盘列表的 RemoteFile（id: Long）会让 Gson 反序列化直接抛 JsonSyntaxException，
 * 整个"解析分享文件夹"在反序列化阶段就挂掉（用户可见现象：解析无反应/直接失败）。
 */
data class ShareFileItem(
    val id: String? = null,
    val nameAll: String? = null,
    val size: String? = null,
    val time: String? = null,
    val icon: String? = null,
    val duan: String? = null
)
