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
                doUpload(file, folderId, uploadName)
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

    /** 实际调用 fileup.php（suspend：Retrofit 接口是挂起函数） */
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
        return if (resp.zt == 1) {
            UploadResult(uploadName, resp.text?.id, true)
        } else {
            UploadResult(uploadName, null, false, resp.info ?: "上传失败（zt=${resp.zt}）")
        }
    }

    /** 需要伪装后缀的格式：exe/apk 等可执行/安装包（蓝奏云限制上传的格式） */
    private fun needsSpoof(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("exe", "apk", "msi", "bat", "sh", "dll", "jar")
    }
}
