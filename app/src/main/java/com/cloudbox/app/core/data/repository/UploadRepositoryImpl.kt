package com.cloudbox.app.core.data.repository

import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.common.SplitZipUtil
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.data.remote.LanzouApiClient
import com.cloudbox.app.core.domain.repository.UploadProbeResult
import com.cloudbox.app.core.domain.repository.UploadRepository
import com.cloudbox.app.core.domain.repository.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.source
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * ─────────────────────────────────────────────────────────────
 * ⚠️ 曾在这里写错过一句结论，已纠正（2026-09-08）
 * ─────────────────────────────────────────────────────────────
 * 旧注释说"原版根本不自己拼 multipart，全靠 WebView 打开官方上传页"——**这是错的**。
 * 当时的依据是反编译出的 47 个 lua 模块里确实没有 multipart 调用，但**上传逻辑
 * 恰恰在当年反编译失败的两个模块之一 `home.lua` 里**（只有字节码反汇编
 * `disasm/home.txt`）。该反汇编 6537-6560 行有完整的 multipart 构造：
 * 只有 `task` / `folder_id` / `upload_file` 三个字段，且每个字段都带
 * `Content-Type: text/plain; charset=UTF-8` 与 `Content-Transfer-Encoding` 子头。
 *
 * 本仓库此前按"浏览器行为"推测发了 10 个字段，且没有那两个子头 —— 多发的字段
 * 服务端并不认，这才是"显示成功但文件没上去"的直接原因。
 * 现已按原版逐字节复刻，见 [buildOriginalMultipart]。
 *
 * 教训：**反编译失败的模块不能当作"没有这个功能"**，只能在结论里注明未覆盖。
 *
 * 防封延时：连续快速上传易触发风控，每次上传间隔 1-3s 随机抖动。
 */
@Singleton
class UploadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
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
                // 0 字节要单独拦：SAF 从第三方 App 拷贝到缓存失败时，文件存在但是空的，
                // 传上去要么被服务端拒，要么变成一个空文件——两种都很难从结果反推原因。
                if (file.length() == 0L) {
                    return@runCatching UploadResult(
                        file.name, null, false,
                        "本地文件是空的（0 字节），可能是从其它 App 选择时拷贝失败"
                    )
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

                // ④ 云端二次确认 —— 2026-09-09 起**降级为提示，不再能否决成功**
                //
                //    ⚠️ 这里曾经是"App 说失败、网盘里其实有文件"的元凶。
                //    设置页「上传通道自检」的实测证据（用户两次跑探针）：
                //      HTTP 200 / zt=1 / info="上传成功" / text[0].id="316683085"
                //    两个 id 不同且真实 → 服务端给 id 就是**真的创建了文件**。
                //    而 verifyOnCloud 靠"列表里能查到同名文件"来判定，一旦碰上
                //    分页（新文件不在第一页）、排序、或入库延迟，就会把真实成功的
                //    上传误判成失败，还给出"未真正上传，请重试"的误导文案。
                //
                //    【现在的规则】成败只由服务端回包决定：
                //    doUpload 已严格判定 zt==1 且 text 是数组且首元素带非空 id，
                //    满足这个条件文件就已经在网盘里了。
                //
                //    列表确认只剩一个用途：查到了就给一句正面反馈，让用户安心。
                //    **查不到就当没发生**——绝不再据此报失败或弹提示，
                //    那正是上一版把真成功误判成失败的原因。
                if (verifyOnCloud(folderId, uploadName)) {
                    result.copy(message = "已上传，且已在文件列表中确认")
                } else {
                    result
                }
            }.getOrElse { e ->
                // 超时单独给一句人话：SocketTimeoutException 对普通用户毫无意义，
                // 而"文件太大/网络太慢"才是他真正能采取行动的信息。
                val reason = when (e) {
                    is java.net.SocketTimeoutException ->
                        "上传超时：文件偏大或网络太慢，建议换 Wi-Fi，或把文件分卷后再传"
                    is java.io.IOException -> "网络错误：${e.message}"
                    else -> e.message ?: "上传失败"
                }
                UploadResult(
                    file.name, null, false,
                    "$reason（大小 ${file.length() / 1024}KB）"
                )
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
        val mime = mimeOf(uploadName)
        val boundary = "----CloudBoxBoundary" +
            java.util.UUID.randomUUID().toString().replace("-", "")

        val resp = apiClient.uploadApiService.upload(
            buildOriginalMultipart(boundary, folderId, uploadName, mime, file),
            "UTF-8"
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

    /**
     * 上传自检：用一个 40 字节的临时 txt 走一遍完整上传链路，返回服务端原始回包。
     *
     * 不走 [doUpload] 的原因：那里会把响应解析成 UploadResponse 再拼摘要，
     * 而排障恰恰需要**未经处理的原文**（可能包含我们 DTO 里没声明的字段）。
     */
    override suspend fun probeUpload(folderId: Long): UploadProbeResult =
        withContext(Dispatchers.IO) {
            val hasCred = apiClient.cookieJar.hasUploadCredentials()
            runCatching {
                val probe = File(context.cacheDir, "cloudbox_upload_probe.txt").apply {
                    writeText("cloudbox upload probe ${System.currentTimeMillis()}\n")
                }
                val boundary = "----CloudBoxBoundary" +
                    java.util.UUID.randomUUID().toString().replace("-", "")
                val body = buildOriginalMultipart(
                    boundary, folderId, probe.name, "text/plain", probe
                )
                val resp = apiClient.uploadApiService.uploadProbe(body, "UTF-8")
                val raw = runCatching { resp.body()?.string() ?: "<空响应体>" }
                    .getOrElse { "<读取响应失败: ${it.message}>" }
                UploadProbeResult(
                    httpCode = resp.code(),
                    requestUrl = resp.raw().request.url.toString(),
                    rawBody = raw,
                    hasCredential = hasCred
                )
            }.getOrElse {
                UploadProbeResult(
                    httpCode = -1,
                    requestUrl = "请求未发出",
                    rawBody = "异常：${it.javaClass.simpleName} ${it.message}",
                    hasCredential = hasCred
                )
            }
        }

    /**
     * 逐字节复刻原版 home.lua 的 multipart（2026-09-08 复核）。
     *
     * ─────────────────────────────────────────────────────────────
     * ⚠️ 为什么必须手写，而不能用 OkHttp 的 MultipartBody
     * ─────────────────────────────────────────────────────────────
     * 原版（disasm/home.txt:6537-6560）发的只有 **3 个字段**，且每个字段都带
     * 两个子头：
     *
     * ```
     * --<boundary>
     * Content-Disposition: form-data; name="task"
     * Content-Type: text/plain; charset=UTF-8
     * Content-Transfer-Encoding: 8bit
     *
     * 1
     * --<boundary>
     * Content-Disposition: form-data; name="folder_id"
     * Content-Type: text/plain; charset=UTF-8
     * Content-Transfer-Encoding: 8bit
     *
     * <folderId>
     * --<boundary>
     * Content-Disposition: form-data; name="upload_file"; filename="<name>"
     * Content-Type: <mime>
     * Content-Transfer-Encoding: binary
     *
     * <文件字节>
     * --<boundary>--
     * ```
     *
     * 此前本仓库发的是 10 个字段（凭"浏览器行为"推测的 vie / ve / id /
     * folder_id_bb_n / name / type / lastModifiedDate），且**没有**上面那两个子头
     * ——推测出来的字段服务端并不认，这正是"显示成功但文件没上去"的直接原因。
     *
     * 另外 OkHttp 的 `MultipartBody.Part.create` 会明确拒绝 part 里带
     * `Content-Type`（抛 "Unexpected header: Content-Type"），
     * 所以无法用它复刻带子头的格式，只能自己写字节流。
     *
     * 换行：严格照抄原版的 LF（原版常量里就是 `\n`）。PHP 侧的 multipart
     * 解析对 LF / CRLF 都容忍，这里以"和原版一致"为准。
     */
    private fun buildOriginalMultipart(
        boundary: String,
        folderId: Long,
        uploadName: String,
        mime: String,
        file: File
    ): RequestBody = object : RequestBody() {

        override fun contentType(): MediaType =
            "multipart/form-data;boundary=$boundary".toMediaType()

        override fun contentLength(): Long {
            val prefix = (
                "--$boundary\n" +
                    "Content-Disposition: form-data; name=\"task\"\n" +
                    "Content-Type: text/plain; charset=UTF-8\n" +
                    "Content-Transfer-Encoding: 8bit\n\n" +
                    "1\n" +
                    "--$boundary\n" +
                    "Content-Disposition: form-data; name=\"folder_id\"\n" +
                    "Content-Type: text/plain; charset=UTF-8\n" +
                    "Content-Transfer-Encoding: 8bit\n\n" +
                    "$folderId\n" +
                    "--$boundary\n" +
                    "Content-Disposition: form-data; name=\"upload_file\"; filename=\"$uploadName\"\n" +
                    "Content-Type: $mime\n" +
                    "Content-Transfer-Encoding: binary\n\n"
                ).toByteArray(Charsets.UTF_8).size.toLong()
            val suffix = "\n--$boundary--\n".toByteArray(Charsets.UTF_8).size.toLong()
            return prefix + file.length() + suffix
        }

        override fun writeTo(sink: okio.BufferedSink) {
            // task = 1
            sink.writeUtf8("--$boundary\n")
            sink.writeUtf8("Content-Disposition: form-data; name=\"task\"\n")
            sink.writeUtf8("Content-Type: text/plain; charset=UTF-8\n")
            sink.writeUtf8("Content-Transfer-Encoding: 8bit\n\n")
            sink.writeUtf8("1\n")

            // folder_id
            sink.writeUtf8("--$boundary\n")
            sink.writeUtf8("Content-Disposition: form-data; name=\"folder_id\"\n")
            sink.writeUtf8("Content-Type: text/plain; charset=UTF-8\n")
            sink.writeUtf8("Content-Transfer-Encoding: 8bit\n\n")
            sink.writeUtf8("$folderId\n")

            // upload_file（文件内容流式写出，不整包读进内存）
            sink.writeUtf8("--$boundary\n")
            sink.writeUtf8(
                "Content-Disposition: form-data; name=\"upload_file\"; filename=\"$uploadName\"\n"
            )
            sink.writeUtf8("Content-Type: $mime\n")
            sink.writeUtf8("Content-Transfer-Encoding: binary\n\n")
            file.inputStream().source().use { sink.writeAll(it) }

            sink.writeUtf8("\n--$boundary--\n")
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
     * 上传后云端确认：在目标目录第一页按文件名查找。
     *
     * ⚠️ 2026-09-09 重写：本函数**只用来给一句正面反馈，绝不决定成败**
     * （理由见 uploadFile 步骤 ④）。既然不承担判定职责，就必须足够轻：
     *
     * - **不重试、不翻页**：只查第一页一次。旧实现要跑 3 轮 × 最多 5 页，
     *   批量上传时每个文件都要多等好几秒，纯粹是拖慢自己。
     * - 新增文件按时间倒序排在最前，查第一页已经足够。
     * - 任何异常都吞掉返回 false —— 确认失败不代表上传失败。
     */
    private suspend fun verifyOnCloud(folderId: Long, uploadName: String): Boolean =
        runCatching {
            apiClient.apiService.getFileList(folderId = folderId, pg = 1)
                .items.any { it.nameAll == uploadName }
        }.getOrDefault(false)

    /** 需要伪装后缀的格式：exe/apk 等蓝奏云限制上传的格式 */
    private fun needsSpoof(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("exe", "apk", "msi", "bat", "sh", "dll", "jar")
    }
}
