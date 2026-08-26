package com.cloudbox.app.core.domain.repository

import java.io.File

/** 单个文件的上传结果 */
data class UploadResult(
    val fileName: String,
    val fileId: String?,
    val success: Boolean,
    val message: String = ""
)

/**
 * 上传仓库。
 *
 * 大小限制策略（需求规格 4 节，严格执行）：
 * - 免费用户单文件上限约 100MB（以接口实际返回为准）
 * - 不写死任何"登录后自动放宽"逻辑（会员额度 200M-210M 仅为社区传闻，无权威佐证）
 * - 超限文件 → [isOversize] 返回 true，UI 引导走分卷流程
 */
interface UploadRepository {

    /** 当前文件是否超过直传限额 */
    fun isOversize(file: File): Boolean

    /** 单文件直传（不支持格式会按设置伪装后缀） */
    suspend fun uploadFile(file: File, folderId: Long, spoofSuffix: Boolean): UploadResult

    /** 大文件分卷上传：先切卷再逐个上传，卷间加 1-3s 随机延时防风控 */
    suspend fun uploadSplit(file: File, folderId: Long): List<UploadResult>

    /** 批量上传队列（WorkManager 用） */
    suspend fun uploadBatch(files: List<File>, folderId: Long, spoofSuffix: Boolean): List<UploadResult>
}
