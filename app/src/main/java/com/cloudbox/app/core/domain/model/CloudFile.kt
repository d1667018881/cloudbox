package com.cloudbox.app.core.domain.model

/** 网盘条目：文件夹或文件（统一模型，UI 列表用） */
data class CloudFile(
    val id: Long,
    val name: String,
    val isFolder: Boolean,
    val size: String?,   // 原样字符串（"12.3 MB"），蓝奏云无字节数值
    val time: String?,
    val onof: String?,   // 是否设提取码
    val isDes: String?,  // 是否有描述
    val parentId: Long
)

/** 一页文件列表结果 */
data class FileListPage(
    val folders: List<CloudFile>,
    val files: List<CloudFile>,
    val hasMore: Boolean
)
