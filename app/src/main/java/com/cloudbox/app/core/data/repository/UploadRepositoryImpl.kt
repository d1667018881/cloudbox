package com.cloudbox.app.core.data.repository

import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.common.ApiError
import com.cloudbox.app.common.SplitZipUtil
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.data.remote.LanzouApiClient
import com.cloudbox.app.core.domain.repository.UploadRepository
import com.cloudbox.app.core.domain.repository.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上传仓库实现（fileup.php）。
 *
 * 参数与需求规格的差异：源码（LanZouCloud-API 2025）实际参数为
 * task=1&vie=2&ve=2&id=WU_FILE_0&folder_id_bb_n=<folderId>&name=<文件名>
 * + 文件二进制 multipart，按源码实现。
 *
 * 防封延时（为什么 1-3s 随机）：蓝奏云对上传频率有风控，连续快速上传多个文件
 * 易触发临时封禁（社区多方验证 + 源码 set_upload_delay 同款思路）。
 * 每次上传间隔 1-3s 随机抖动，既避免风控又不至于太慢。
 */
@Singleton
class UploadRepositoryImpl @Inject constructor(
    private val apiClient: LanzouApiClient,
    private val settingsStore: SettingsStore
) : UploadRepository {

    override fun isOversize(file: File): Boolean =
        file.length() > AppConstants.FREE_FILE_LIMIT_BYTES

    override suspend fun uploadFile(file: File, folderId: Long, spoofSuffix: Boolean): UploadResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // 后缀伪装总开关在设置页（默认开）：关闭时即使传了 spoofSuffix=true 也不改名
                val effSpoof = spoofSuffix && settingsStore.suffixSpoofEnabled.first()
                val uploadName = if (effSpoof && needsSpoof(file)) {
                    // 后缀伪装：蓝奏云不允许上传 exe/apk 等格式，改名 .zip 上传，下载时还原
                    "${file.name}.zip"
                } else {
                    file.name
                }
                val result = doUpload(file, folderId, uploadName)
                if (!result.success) return@runCatching result
                // 假成功兜底：fileup.php 返回 zt=1 不代表文件真的入库（用户实测照片上传失败仍报成功）。
                // 上传后查一次云端目录（task=5 第一页按时间倒序，刚上传的排最前），确认文件真实存在。
                when (verifyOnCloud(folderId, uploadName)) {
                    false -> UploadResult(
                        uploadName, null, false,
                        "服务器返回成功，但云端目录未找到该文件（可能未真正上传，请重试）"
                    )
                    else -> result // true=已确认存在 / null=列表请求失败无法确认（按成功处理，不误报）
                }
            }.getOrElse { e ->
                UploadResult(file.name, null, false, e.message ?: "上传失败")
            }
        }

    override suspend fun uploadSplit(file: File, folderId: Long): List<UploadResult> =
        withContext(Dispatchers.IO) {
            val tmpDir = File(file.parentFile, ".cloudbox_split_${System.currentTimeMillis()}")
            tmpDir.mkdirs()
            try {
                val volumes = SplitZipUtil.split(file, tmpDir)
                val results = mutableListOf<UploadResult>()
                volumes.forEachIndexed { index, volume ->
                    // 卷间随机延时 1-3s：防止触发风控封号（需求规格 4 节）
                    val delayMs = ThreadLocalRandom.current().nextLong(
                        AppConstants.BATCH_DELAY_MIN_MS, AppConstants.BATCH_DELAY_MAX_MS + 1
                    )
                    if (index > 0) delay(delayMs)
                    results.add(uploadFile(volume, folderId, spoofSuffix = false))
                }
                results
            } finally {
                tmpDir.deleteRecursively()
            }
        }

    override suspend fun uploadBatch(files: List<File>, folderId: Long, spoofSuffix: Boolean): List<UploadResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<UploadResult>()
            files.forEachIndexed { index, file ->
                if (index > 0) {
                    delay(ThreadLocalRandom.current().nextLong(
                        AppConstants.BATCH_DELAY_MIN_MS, AppConstants.BATCH_DELAY_MAX_MS + 1
                    ))
                }
                results.add(
                    if (isOversize(file)) {
                        // 超限文件自动走分卷（需求规格：拒绝直传 + 引导分卷）
                        uploadSplit(file, folderId).also { splitResults ->
                            // 分卷结果合并为一条摘要
                        }.let { split ->
                            val ok = split.count { it.success }
                            UploadResult(file.name, null, ok == split.size, "分卷 $ok/${split.size} 成功")
                        }
                    } else {
                        uploadFile(file, folderId, spoofSuffix)
                    }
                )
            }
            results
        }

    /** 实际调用 fileup.php（suspend：Retrofit 接口是挂起函数）。
     *  成功判定加严：zt==1 且响应带文件 id——缺 id 视为异常响应，防止假成功。 */
    private suspend fun doUpload(file: File, folderId: Long, uploadName: String): UploadResult {
        val mediaType = "application/octet-stream".toMediaType()
        val filePart = MultipartBody.Part.createFormData(
            "upload_file", uploadName, file.asRequestBody(mediaType)
        )
        val resp = apiClient.apiService.upload(
            task = "1".toRequestBody(mediaType),
            vie = "2".toRequestBody(mediaType),
            ve = "2".toRequestBody(mediaType),
            id = "WU_FILE_0".toRequestBody(mediaType),
            folderId = folderId.toString().toRequestBody(mediaType),
            name = uploadName.toRequestBody(mediaType),
            file = filePart
        )
        val fileId = resp.text?.id
        return if (resp.zt == 1 && !fileId.isNullOrBlank()) {
            UploadResult(uploadName, fileId, true)
        } else if (resp.zt == 1) {
            UploadResult(uploadName, null, false, "服务器返回成功但未返回文件ID（疑似未真正上传）")
        } else {
            UploadResult(uploadName, null, false, resp.info ?: "上传失败（zt=${resp.zt}）")
        }
    }

    /** 上传后云端确认：在目标目录第一页（按时间倒序）查找刚上传的文件。
     *  返回 true=已确认存在；false=目录里没有（假成功）；null=列表请求失败，无法判断。
     *  注意 task=5 列表的文件名字段是 name_all。 */
    private suspend fun verifyOnCloud(folderId: Long, uploadName: String): Boolean? =
        runCatching {
            val resp = apiClient.apiService.getFileList(folderId = folderId, pg = 1)
            resp.text?.any { it.nameAll == uploadName }
        }.getOrNull()

    /** 需要伪装后缀的格式：exe/apk 等可执行/安装包（蓝奏云限制上传的格式） */
    private fun needsSpoof(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("exe", "apk", "msi", "bat", "sh", "dll", "jar")
    }
}
