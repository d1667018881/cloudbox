package com.cloudbox.app.core.domain.model

/**
 * 星标文件夹（本地聚合数据，不属于服务端）。
 *
 * 对齐原版「星标文件夹」语义（home_file.lua「星标文件夹说明」）：
 * - 星标时**保存当时的文件夹信息**（快照），之后源文件夹改名/变动不会自动同步；
 * - 删除已星标的文件夹后条目仍然保留，需用户自己取消星标；
 * - 不同账号独立存储，退出登录会一并清掉该账号的星标数据。
 *
 * [sortOrder] 是排序权重（越小越靠前）。原版在星标页启用「名称排序」后会把
 * 结果落盘保存，所以排序不是纯 UI 状态。
 */
data class StarredFolder(
    val folderId: Long,
    val name: String,
    val remark: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = 0L
)
