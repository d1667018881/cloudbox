package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.DirectLink

/** 文件夹递归解析结果（#13 修复：保留失败信息，不静默丢弃） */
data class ResolveFolderResult(
    val links: List<DirectLink>,
    val failedCount: Int,
    val totalCount: Int
)

/** 直链解析仓库 */
interface DirectLinkRepository {

    /**
     * 解析单条分享链接为直链（带密码支持，自动缓存 TTL 1h）。
     * 流程（需求规格 7 节）：GET 分享页 → 提取 sign → POST ajaxm.php → 拼接直链。
     */
    suspend fun resolve(shareUrl: String, password: String = ""): Result<DirectLink>

    /** 批量解析（每条间隔 1-3s 随机延时，防风控拉黑） */
    suspend fun resolveBatch(shareUrls: List<Pair<String, String>>): List<Result<DirectLink>>

    /** 解析文件夹分享：递归列出文件夹内全部文件并解析直链（含失败计数） */
    suspend fun resolveFolder(shareUrl: String, password: String = ""): Result<ResolveFolderResult>
}
