package com.cloudbox.app.core.data.repository

import com.cloudbox.app.common.AppConstants
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
 * 上传仓库实现（html5up.php，2026-09 按线上实测 + 原版 App 行为重写）。
 *
 * ─────────────────────────────────────────────────────────────
 * ⚠️ "显示成功但文件没上去"是怎么发生的（本版根除）
 * ─────────────────────────────────────────────────────────────
 * 旧实现有三条通向假成功的路径，逐条封死：
 *
 * 1) 云端确认失败被当成成功：verifyOnCloud 用 runCatching{}.getOrNull()，
 *    列表请求一旦失败就返回 null，旧代码对 null 分支返回 success=true
 *    （只附一句"未确认"提示）→ 用户看到"上传完成"，云端却没有文件。
 *    【本版】null 一律判定为**失败**，宁可让用户重试，也不给假成功。
 *
 * 2) 缺 Referer：html5up.php 在 Referer 缺失/不当时不报错，返回 zt=1 却不入库。
 *    【本版】由 LanzouRefererInterceptor 自动补全为网盘文件页。
 *
 * 3) 凭证不完整：只有 phpdisk_info 缺失时服务端才明确返回 zt=9；
 *    凭证"半失效"时同样可能返回 zt=1 不入库。
 *    【本版】上传前先自检 phpdisk_info，缺失直接失败并提示重新登录。
 *
 * 另外补一个原版行为对照：原版（蓝云 AndroLua）**根本不自己拼 multipart**——
 * 它直接 WebView 打开官方上传页交给网页 JS 处理（反编译 55 个 lua 模块 +
 * Http.java 全部调用点确认：无任何 multipart 上传调用）。这是它"能正常上传"的
 * 真正原因。因此本仓库同时保留 [uploadPageUrl] 网页上传通道，
 * 原生直传失败时 UI 可引导用户走官方页面兜底。
 *
 * 防封延时：连续快速上传易触发风控，每次上传间隔 1-3s 随机抖动。
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
                if (!file.exists() || !file.isFile) {
                    return@runCatching UploadResult(file.name, null, false, "本地文件不存在")
                }
                // ① 凭证自检：没有 phpdisk_info 就不要发请求（否则大概率假成功）
                if (!apiClient.cookieJar.hasUploadCredentials()) {
                    return@runCatching UploadResult(
                        file.name, null, false, "未登录或登录态已失效，请重新登录后再上传"
                    )
                }

                // ② 后缀伪装总开关在设置页（默认开）：关闭时即使传了 spoofSuffix=true 也不改名
                val effSpoof = spoofSuffix && settingsStore.suffixSpoofEnabled.first()
                val uploadName = if (effSpoof && needsSpoof(file)) {
                    // 蓝奏云不接受 exe/apk 等格式，改名 .zip 上传，下载时还原
                    "${file.name}.zip"
                } else {
                    file.name
                }

                // ③ 直传
                val result = doUpload(file, folderId, uploadName)
                if (!result.success) return@runCatching result

                // ④ 云端确认（关键）：zt=1 不代表真的入库，必须回查目录确认。
                //    verifyOnCloud 内部已重试 3 次（0/1.2s/2.4s），这里不再叠加外层重试
                //    ——批量上传时每多一轮就是每个文件多等几秒。
                val verified = verifyOnCloud(folderId, uploadName)
                when (verified) {
                    true -> result
                    false -> UploadResult(
                        uploadName, result.fileId, false,
                        "服务器返回成功，但云端目录未找到该文件（未真正上传，请重试）"
                    )
                    // 旧实现在这里返回 success=true —— 假成功的唯一入口，本版改为失败
                    null -> UploadResult(
                        uploadName, result.fileId, false,
                        "上传结果无法确认：云端列表请求失败，请稍后在文件列表核实"
                    )
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
                    if (index > 0) {
                        delay(ThreadLocalRandom.current().nextLong(
                            AppConstants.BATCH_DELAY_MIN_MS, AppConstants.BATCH_DELAY_MAX_MS + 1
                        ))
                    }
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
                        val split = uploadSplit(file, folderId)
                        val ok = split.count { it.success }
                        UploadResult(file.name, null, ok == split.size, "分卷 $ok/${split.size} 成功")
                    } else {
                        uploadFile(file, folderId, spoofSuffix)
                    }
                )
            }
            results
        }

    // ==================== 内部实现 ====================

    /**
     * 网页上传通道：返回官方上传页地址（原版 App 走的就是这条路）。
     * 原生直传被风控/协议变更挡住时，UI 可用 WebView 打开它兜底。
     *
     * 带上目标文件夹时蓝奏云网页会在该目录下上传；不同站点版本对
     * folder_id 的支持不一致，故只在 folderId > 0 时附加。
     */
    override fun uploadPageUrl(folderId: Long): String {
        val disk = apiClient.domainInterceptor.snapshot().diskMain.trimEnd('/')
        return if (folderId > 0) "$disk/mydisk.php?item=files&action=index&folder_id=$folderId"
        else "$disk/mydisk.php?item=files&action=index"
    }

    /**
     * 实际调用 html5up.php（2026-09 实测 fileup.php 已 404 下线）。
     *
     * 成功判定收紧为：zt==1 **且** text 是数组 **且** 首元素带非空 id。
     * 未登录时 text 是字符串 "error"，zt=9；凭证/Referer 异常时可能 zt=1 但
     * text 不含 id —— 这些一律视为失败，绝不放行。
     */
    private suspend fun doUpload(file: File, folderId: Long, uploadName: String): UploadResult {
        val mediaType = "application/octet-stream".toMediaType()
        val filePart = MultipartBody.Part.createFormData(
            "upload_file", uploadName, file.asRequestBody(mediaType)
        )
        val mime = mimeOf(uploadName)
        // 浏览器 File.lastModifiedDate 的序列化格式
        val lastModified = java.text.SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT'Z (z)", java.util.Locale.ENGLISH
        ).format(java.util.Date(file.lastModified()))

        val resp = apiClient.apiService.upload(
            task = "1".toRequestBody(mediaType),
            vie = "2".toRequestBody(mediaType),
            ve = "2".toRequestBody(mediaType),
            id = "WU_FILE_0".toRequestBody(mediaType),
            folderIdBbN = folderId.toString().toRequestBody(mediaType),
            // 双写 folder_id：不同站点版本认的字段名不同，多传一个无害
            folderId = folderId.toString().toRequestBody(mediaType),
            name = uploadName.toRequestBody(mediaType),
            type = mime.toRequestBody(mediaType),
            lastModifiedDate = lastModified.toRequestBody(mediaType),
            file = filePart
        )

        val fileId: String? = (resp.text as? List<*>)
            ?.firstOrNull()
            ?.let { entry -> (entry as? Map<*, *>)?.get("id")?.toString() }

        // 把服务端原始响应摘要进失败原因：只有看到 zt + info + text 的真实形态，
        // 才能判断是"未登录""参数不对"还是"服务端假成功"，而不是靠猜。
        val raw = "zt=${resp.zt}, info=${resp.info ?: "空"}, text=${resp.text.brief()}"

        return when {
            resp.zt == 1 && !fileId.isNullOrBlank() ->
                UploadResult(uploadName, fileId, true)
            resp.zt == 9 || resp.info?.contains("login", true) == true ->
                UploadResult(uploadName, null, false,
                    "登录态已失效，请重新登录（$raw）")
            resp.zt == 1 ->
                UploadResult(uploadName, null, false,
                    "服务端回了成功却没给文件 ID，未真正上传（$raw）")
            else ->
                UploadResult(uploadName, null, false,
                    "${resp.info?.takeIf { it.isNotBlank() } ?: "上传失败"}（$raw）")
        }
    }

    /** 任意响应体的简短画像：仅供失败诊断展示，不参与成功判定 */
    private fun Any?.brief(): String = when (this) {
        null -> "空"
        is String -> "字符串「${if (length > 40) take(40) + "…" else this}」"
        is List<*> -> "数组(${size} 项)"
        is Map<*, *> -> "对象{${keys.take(5).joinToString()}}"
        else -> toString().take(40)
    }

    /** 按扩展名取 MIME（html5up 的 type 字段；模拟浏览器 File.type） */
    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "application/octet-stream"
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /**
     * 上传后云端确认：在目标目录第一页（按时间倒序，刚上传的排最前）查文件名。
     *
     * 返回：true=确认存在；false=目录里确实没有（假成功）；null=列表请求失败，无法判断。
     * 注意区分 false 与 null：只有请求本身成功、但列表里没有该文件，才是 false。
     */
    private suspend fun verifyOnCloud(folderId: Long, uploadName: String): Boolean? {
        // 服务端入库有短暂延迟，最多重试 3 次（0 / 1.2s / 2.4s）
        repeat(3) { attempt ->
            if (attempt > 0) delay(1_200L * attempt)
            val listed: Boolean? = runCatching {
                val resp = apiClient.apiService.getFileList(folderId = folderId, pg = 1)
                resp.items.any { it.nameAll == uploadName }
            }.getOrNull()
            if (listed == true) return true
            // 请求成功但没找到 → 再等一轮；连续 3 轮都没有才判 false
            if (listed == false && attempt == 2) return false
        }
        return null
    }

    /** 需要伪装后缀的格式：exe/apk 等蓝奏云限制上传的格式 */
    private fun needsSpoof(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("exe", "apk", "msi", "bat", "sh", "dll", "jar")
    }
}
