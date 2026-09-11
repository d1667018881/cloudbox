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
 * 上传自检（探针）结果：把服务端原始回包摊开，便于定位"假成功"。
 *
 * @param httpCode  HTTP 状态码（-1 表示请求根本没发出去）
 * @param requestUrl 实际请求的完整 URL（确认域名/端点是否符合预期）
 * @param rawBody   服务端返回的原始文本（**不截断**，包含 zt / info / text 全部字段）
 * @param fileName  被测文件名（判断"是不是这个文件本身有问题"时要用）
 * @param fileSize  被测文件字节数
 * @param targetFolderId 目标目录 id（-1 = 根目录）。必须带上：根目录传得上去 ≠ 子目录传得上去，
 *                  而此前设置页自检写死根目录，用户实际失败的多在子目录，压根复现不了。
 */
data class UploadProbeResult(
    val httpCode: Int = -1,
    val requestUrl: String = "",
    val rawBody: String = "",
    val hasCredential: Boolean = false,
    val fileName: String = "",
    val fileSize: Long = 0,
    val targetFolderId: Long = -1,
    /**
     * 实际提交给服务端的文件名（可能与 [fileName] 不同：后缀伪装开启时会加 .zip）。
     * 空串表示未改名。
     *
     * 必须带上：探针若用原名、真实上传却用伪装名，那"探针成功"就不能证明
     * 真实上传没问题——失败恰恰可能出在改名后的文件名上。
     */
    val uploadAs: String = ""
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

    /**
     * 官方网页上传页地址（**仅作兜底**，不是默认通道）。
     *
     * ⚠️ 本注释曾经写反过，2026-09-08 更正：
     * 旧注释称"原版 App 并不自己拼 multipart，全靠 WebView 网页上传"——这是错的。
     * 错因：home.lua / webview.lua 当年反编译失败，只剩字节码反汇编，当时没深挖，
     * 把"没找到"当成了"不存在"。
     * 真相在 disasm/home.txt:6537-6560：原版**确实**自己拼 multipart，
     * 且只有 3 个字段（task / folder_id / upload_file），每个字段都带
     * `Content-Type` + `Content-Transfer-Encoding` 子头。
     * 教训：反编译失败的模块只能标注"未覆盖"，不能反推成"没有该功能"。
     *
     * 本仓库已按原版逐字节复刻（见 UploadRepositoryImpl.buildOriginalMultipart），
     * 默认走原生直传；本地址只在直传异常时由用户手动选用。
     */
    fun uploadPageUrl(folderId: Long): String

    /**
     * 上传通道自检（探针）：往目标目录传一个几十字节的临时 txt，
     * 把服务端**原始回包**完整返回供排障。
     *
     * 为什么需要它：html5up.php 在参数不符时往往不报错，而是回 `zt=1` 却不入库
     * （表现为"显示成功、云端没有文件"）。只看 App 的成功/失败文案无法定位，
     * 必须看到 zt / info / text 的真实形态。
     */
    suspend fun probeUpload(folderId: Long): UploadProbeResult

    /**
     * 用**指定文件**跑自检（排障主力）。
     *
     * 为什么必须有它：内置探针只有 40 字节，能过不代表真实文件能过。
     * 文件太大（超时）、格式受限、文件名编码异常，这些只有拿真文件测才暴露得出来。
     * 用户拿那个"一直失败的文件"点一下，回包原文就能直接定性。
     */
    suspend fun probeUploadWith(file: File, folderId: Long): UploadProbeResult

    /** 单文件直传（不支持格式会按设置伪装后缀） */
    suspend fun uploadFile(file: File, folderId: Long, spoofSuffix: Boolean): UploadResult

    /** 大文件分卷上传：先切卷再逐个上传，卷间加 1-3s 随机延时防风控 */
    suspend fun uploadSplit(file: File, folderId: Long): List<UploadResult>

    /** 批量上传队列（WorkManager 用） */
    suspend fun uploadBatch(files: List<File>, folderId: Long, spoofSuffix: Boolean): List<UploadResult>
}
