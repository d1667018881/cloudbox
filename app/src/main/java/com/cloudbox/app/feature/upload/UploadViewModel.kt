package com.cloudbox.app.feature.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cloudbox.app.core.domain.repository.UploadProbeResult
import com.cloudbox.app.core.domain.repository.UploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * 上传调度 ViewModel（V5 重构：上传并入网盘页 FAB，不再是独立 Tab）。
 *
 * 使用方式：FileListScreen 的 + FAB → SAF 多选 → [enqueueUpload]（目标 =
 * 当前文件夹）→ 底部进度横幅 → [uploadFinished] 事件触发列表刷新。
 *
 * 进度语义（多批合并）：WorkManager 按每批 ≤50 文件链式串联（Data 10KB 上限），
 * 全局进度 = 已完成批次的文件数累计 + 当前批次的批内进度，total = 全部文件数
 * （修复 V3 P3 的"total 随批次跳变"问题）。
 *
 * 失败语义（V3 N2）：Worker 一律返回 success，失败名单走 outputData，
 * 这里汇总 failedAccumulator 判定"全部成功/部分失败"。
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadRepository: UploadRepository,
    private val workManager: WorkManager
) : ViewModel() {

    /** 上传进度横幅状态 */
    data class UploadUiState(
        val uploading: Boolean = false,
        val progress: Int = 0,
        val total: Int = 0,
        val currentFile: String = "",
        val message: String? = null,
        val failedFiles: List<String> = emptyList(),
        /**
         * 本轮是否存在失败。UI 靠它决定 Snackbar 的停留时长与"改走网页上传"入口。
         *
         * 不要拿 failedFiles.isEmpty() 代替：用户 dismiss 后 failedFiles 仍在，
         * 需要一个能随会话重置的独立标志。
         */
        val hasFailure: Boolean = false,
        /** 真实文件诊断进行中（大文件可能要等一会儿，必须给个反馈） */
        val diagnosing: Boolean = false
    )

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    /** 一次上传会话结束（不论成败）发射一次；网盘页收集后刷新当前文件夹列表。
     *  replay=1：Tab 切换瞬间完成的事件在新订阅者建立时补投一次（进入网盘页顺手刷新） */
    private val _uploadFinished = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val uploadFinished: SharedFlow<Unit> = _uploadFinished.asSharedFlow()

    /**
     * 上传过程的**结构化时间线**，专供排障。
     *
     * 为什么要留这个：上传链路是"ViewModel 建任务 → WorkManager 调度 → Worker 执行
     * → 回写 outputData → ViewModel 汇总"五段式的，出问题时空口无凭，只能靠猜。
     * 把每段的关键事实按顺序记下来，出问题时导出一次就能定位到具体哪一段断了，
     * 而不是反复改代码试。
     *
     * 只保留最近 [TIMELINE_MAX] 条，避免长会话占内存。
     */
    private val _timeline = MutableStateFlow<List<String>>(emptyList())
    val timeline: StateFlow<List<String>> = _timeline.asStateFlow()

    private fun trace(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        Log.i(TAG, line)
        _timeline.update { (it + "$stamp  $line").takeLast(TIMELINE_MAX) }
    }

    private var currentWorkIds: List<UUID> = emptyList()
    private val workStates = mutableMapOf<UUID, WorkInfo.State>()
    private val failedAccumulator = mutableListOf<String>()
    /** 失败原因（去重）：用户最需要的是"为什么失败"，而不是一句"部分失败" */
    private val failedReasons = mutableListOf<String>()
    /**
     * 被系统中断（FAILED/CANCELLED）的文件数。
     * ⚠️ 这些**不算成功**：旧实现把它们当成"完成且无失败"，直接报全成功。
     */
    private var abortedFiles = 0
    private val abortedReasons = mutableListOf<String>()
    private var globalTotal = 0

    /**
     * 最近一次 [copyUriToCache] 失败的具体原因。
     *
     * 为什么要有它：旧实现里 copyUriToCache 只返回 File?，失败一律显示
     * 一句"所选文件读取失败"，用户和我们都不知道是空文件、断流还是没权限。
     * 这类失败恰恰是"秒成功/秒失败"的高发区，必须能说出具体是哪一种。
     */
    private var lastCopyError: String? = null

    init {
        // V5 自查修复：进程在上传中被杀后重进 App，ViewModel 重建会丢失对在途
        // Worker 的观察——后台传完后 uploadFinished 无人发射，列表又不刷新了
        // （正是 V5 主修缺陷的残留路径）。凭固定 tag 找出全部上传批次，
        // 再按会话 uuid 分组，只接管含未完成批的那个会话（S5：防多会话混淆）。
        viewModelScope.launch {
            val infos = runCatching { workInfosByTag(UploadWorker.TAG_UPLOAD_SESSION) }
                .getOrDefault(emptyList())
            if (infos.isEmpty()) return@launch

            fun sessionOf(info: WorkInfo): String? =
                info.tags.firstOrNull { it.startsWith(UploadWorker.TAG_SESSION_PREFIX) }
                    ?.removePrefix(UploadWorker.TAG_SESSION_PREFIX)

            // 含未完成批的会话（正常至多一个；旧会话已全部终态则被排除）
            val activeSession = infos.groupBy(::sessionOf)
                .entries.firstOrNull { (_, list) -> list.any { !it.state.isFinished } }
                ?.value ?: return@launch

            fun batchSizeOf(info: WorkInfo): Int =
                info.progress.getInt(UploadWorker.KEY_TOTAL, 0).takeIf { it > 0 }
                    ?: info.tags.firstOrNull { it.startsWith(UploadWorker.TAG_SIZE_PREFIX) }
                        ?.substringAfterLast(':')?.toIntOrNull() ?: 0

            val active = activeSession.filter { !it.state.isFinished }
            currentWorkIds = active.map { it.id }
            workStates.clear()
            failedAccumulator.clear()
            failedReasons.clear()
            abortedFiles = 0
            abortedReasons.clear()
            // 本次会话中已完成批（SUCCEEDED）的失败名单与文件数一并并入，进度从正确基数续算
            activeSession.filter { it.state.isFinished }.forEach { info ->
                if (info.state == WorkInfo.State.SUCCEEDED) {
                    info.outputData.getString(UploadWorker.KEY_FAILED_FILES)
                        ?.split("\n")?.filter { it.isNotBlank() }
                        ?.let { failedAccumulator.addAll(it) }
                } else {
                    // FAILED/CANCELLED 同样是"没传上去"，必须与 observeWorks 口径一致，
                    // 否则恢复会话时又会把中断的文件算成成功。
                    abortedFiles += batchSizeOf(info)
                    abortedReasons.add("上传任务被中断（超过 10 分钟或进程被回收）")
                }
            }
            val initialFinished = activeSession
                .filter { it.state == WorkInfo.State.SUCCEEDED }
                .sumOf { batchSizeOf(it) }
            globalTotal = activeSession.filter { it.state != WorkInfo.State.CANCELLED }.sumOf { batchSizeOf(it) }

            abortedFiles = 0
            abortedReasons.clear()
            _uiState.update {
                it.copy(
                    uploading = true, progress = initialFinished,
                    total = globalTotal, message = null, hasFailure = false
                )
            }
            observeWorks(currentWorkIds, active.map { batchSizeOf(it) }, initialFinished)
        }
    }

    /**
     * WorkManager.getWorkInfosByTag 返回 ListenableFuture（KTX 无 tag 版 Flow 扩展，
     * 只有 LiveData 版），用 suspendCancellableCoroutine 手动桥接，避免引 guava 协程依赖。
     */
    private suspend fun workInfosByTag(tag: String): List<WorkInfo> =
        suspendCancellableCoroutine { cont ->
            val future = workManager.getWorkInfosByTag(tag)
            future.addListener({
                cont.resumeWith(kotlin.runCatching { future.get() })
            }, context.mainExecutor)
        }

    /** SAF 多选入口：拷贝到缓存 → 分批 → 链式入队（folderId = 当前目录，-1 = 根） */
    fun enqueueUpload(uris: List<Uri>, folderId: Long, spoof: Boolean = true) {
        if (uris.isEmpty()) return
        if (_uiState.value.uploading) {
            _uiState.update { it.copy(message = "已有上传任务进行中，请稍候") }
            return
        }
        // 防重入（V5 自查修复）：先占位 uploading=true。拷贝期间（大文件可达数秒）
        // 用户再次点上传会双会话并行，currentWorkIds 互相覆盖、进度混乱。
        _uiState.update { it.copy(uploading = true, message = null) }
        viewModelScope.launch {
            // 大文件拷贝必须在 IO 线程（默认 viewModelScope = Main）
            val paths = withContext(Dispatchers.IO) {
                uris.mapNotNull { copyUriToCache(it)?.absolutePath }
            }
            if (paths.isEmpty()) {
                _uiState.update {
                    it.copy(
                        uploading = false,
                        message = lastCopyError ?: "所选文件读取失败",
                        hasFailure = true
                    )
                }
                return@launch
            }

            // 每批文件数。取值权衡：
            //
            //   太大（旧值 50）：单个 Worker 有 **10 分钟**运行时硬上限，超时被系统
            //   中断置为 FAILED，云盘那边就是"传了一半没了"。50 个文件加上防抖延时
            //   很容易撞线。
            //
            //   太小（曾改成 1）：N 个文件 = N 个链式 Worker。链式调度本身没问题，
            //   但任何一个节点被系统压制/中断，后续节点全部不执行；而且每个文件都要
            //   过一次 WorkManager 调度（ENQUEUED→RUNNING→SUCCEEDED 三次状态往返），
            //   多文件时"状态回放"的时序复杂度陡增，正是"秒成功"这类
            //   时序 bug 的温床。
            //
            //   取 5：单个 Worker 最多传 5 个文件，即便都是接近 100MB 的大文件也不会
            //   撞 10 分钟线（5 × 100MB 在 10 分钟内的上行要求远低于家用带宽能力，
            //   且大文件本就会走分卷），同时把 Worker 数量压到原来的 1/5，
            //   时序大幅简化。
            trace("已拷贝 ${paths.size} 个文件到缓存，准备入队（每批 $BATCH_SIZE 个）")
            val sessionUuid = UUID.randomUUID().toString()
            val batches = paths.chunked(BATCH_SIZE)
            // 离线时 Worker 不跑（否则直接把"上传失败"写进名单）；联网后自动继续
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            val requests = batches.map { batch ->
                androidx.work.OneTimeWorkRequestBuilder<UploadWorker>()
                    .addTag(UploadWorker.TAG_UPLOAD_SESSION)
                    .addTag(UploadWorker.TAG_SESSION_PREFIX + sessionUuid)
                    // 批大小随 tag 冗余一份：WorkInfo 不暴露 inputData，进程重启恢复时
                    // ENQUEUED 批的 size 从这里解析（见 init 的 batchSizeOf）
                    .addTag("${UploadWorker.TAG_SIZE_PREFIX}$sessionUuid:${batch.size}")
                    .setConstraints(constraints)
                    .setInputData(
                        androidx.work.Data.Builder()
                            .putLong(UploadWorker.KEY_FOLDER_ID, folderId)
                            .putStringArray(UploadWorker.KEY_FILE_PATHS, batch.toTypedArray())
                            .putBoolean(UploadWorker.KEY_SPOOF, spoof)
                            .build()
                    )
                    .build()
            }
            currentWorkIds = requests.map { it.id }
            workStates.clear()
            failedAccumulator.clear()
            failedReasons.clear()
            abortedFiles = 0
            abortedReasons.clear()
            globalTotal = paths.size

            val continuation = requests.drop(1).fold(
                workManager.beginWith(requests.first())
            ) { cont, req -> cont.then(req) }
            // 入队失败（存储罕见异常）时复位 uploading，否则后续上传被永久挡死
            runCatching { continuation.enqueue() }
                .onSuccess {
                    trace(
                        "任务已入队：${requests.size} 个 Worker（链式串行），" +
                            "首个 id=${requests.first().id.toString().take(8)}"
                    )
                }
                .onFailure { e ->
                    trace("入队失败：${e.javaClass.simpleName} ${e.message}")
                    currentWorkIds = emptyList()
                    workStates.clear()
                    _uiState.update {
                        it.copy(
                            uploading = false, hasFailure = true,
                            message = "上传任务创建失败：${e.message}"
                        )
                    }
                    return@launch
                }

            _uiState.update {
                it.copy(
                    uploading = true,
                    progress = 0,
                    total = globalTotal,
                    message = null,
                    failedFiles = emptyList(),
                    hasFailure = false,
                    currentFile = ""
                )
            }
            observeWorks(currentWorkIds, batches.map { it.size })
        }
    }

    // ==================== 真实文件诊断（排障用） ====================

    /**
     * 拿用户选的真实文件、在**当前目录**下跑一遍完整上传链路。
     *
     * 为什么必须能在网盘页直接跑：设置页的自检固定传根目录（folderId=-1），
     * 而用户实际多半在子目录上传。目录 id 不对、真实文件太大/格式受限，
     * 这些只有在"真实目录 + 真实文件"下才复现得出来。
     */
    private val _probeResult = MutableStateFlow<UploadProbeResult?>(null)
    val probeResult: StateFlow<UploadProbeResult?> = _probeResult.asStateFlow()

    fun diagnoseUpload(uri: Uri, folderId: Long) {
        viewModelScope.launch {
            _probeResult.value = null
            _uiState.update { it.copy(diagnosing = true) }
            val r = withContext(Dispatchers.IO) {
                val f = copyUriToCache(uri)
                if (f == null) {
                    UploadProbeResult(
                        httpCode = -1,
                        requestUrl = "未发出（本地读取阶段就失败了）",
                        rawBody = lastCopyError ?: "无法读取所选文件",
                        hasCredential = false
                    )
                } else {
                    uploadRepository.probeUploadWith(f, folderId)
                }
            }
            _probeResult.value = r
            _uiState.update { it.copy(diagnosing = false) }
        }
    }

    fun dismissProbe() { _probeResult.value = null }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    /** 观察各批次：RUNNING 更新全局进度，终态累计完成数并收集失败名单 */
    private fun observeWorks(
        workIds: List<UUID>,
        batchSizes: List<Int>,
        initialFinished: Int = 0
    ) {
        var finishedCount = initialFinished // 已完成批次累计的文件数（批间串行，无并发写）
        workIds.forEachIndexed { idx, workId ->
            viewModelScope.launch {
                workManager.getWorkInfoByIdFlow(workId).collect { info ->
                    if (info.state == WorkInfo.State.RUNNING) {
                        val p = info.progress.getInt(UploadWorker.KEY_PROGRESS, 0)
                        _uiState.update {
                            it.copy(
                                progress = finishedCount + p,
                                total = globalTotal,
                                currentFile = info.progress.getString(UploadWorker.KEY_CURRENT_FILE) ?: ""
                            )
                        }
                    }
                    // 状态变化全部落日志——"秒成功"排查要靠它还原真实时间线：
                    // 正常应看到 ENQUEUED → RUNNING → SUCCEEDED；
                    // 若只有 ENQUEUED 就直接 SUCCEEDED/FAILED，说明 Worker 没真正执行。
                    if (workStates[workId] != info.state) {
                        trace(
                            "批次 ${workId.toString().take(8)} → $info.state " +
                                "进度=${info.progress.getInt(UploadWorker.KEY_PROGRESS, -1)}" +
                                "/${info.progress.getInt(UploadWorker.KEY_TOTAL, -1)}"
                        )
                    }
                    if (info.state.isFinished && workStates[workId] == null) {
                        workStates[workId] = info.state
                        // ⚠️ 只有 SUCCEEDED 才能读 outputData 判成败。
                        //
                        //    FAILED / CANCELLED 的 outputData 是空的——如果照旧往下走，
                        //    failed 名单为空 → checkAllFinished 报「全部上传成功」。
                        //    这正是 2026-09 那次「App 显示成功、云端一个文件都没有」的
                        //    最后一环：WorkManager 单 Worker 有 10 分钟硬上限，
                        //    大文件/多文件超时被系统掐断 → FAILED → 空 outputData → 假成功。
                        //    现在把这两种终态单独记账，绝不混进成功数里。
                        if (info.state != WorkInfo.State.SUCCEEDED) {
                            abortedFiles += batchSizes.getOrElse(idx) { 1 }
                            abortedReasons += when (info.state) {
                                WorkInfo.State.CANCELLED -> "上传任务被取消"
                                else -> "上传任务被中断（单个文件超过 10 分钟，或进程被系统回收）"
                            }
                            checkAllFinished()
                            return@collect
                        }
                        finishedCount += batchSizes.getOrElse(idx) { 1 }
                        // N2(V3)：Worker 一律 success，失败名单走 outputData
                        info.outputData.getString(UploadWorker.KEY_FAILED_FILES)
                            ?.split("\n")
                            ?.filter { it.isNotBlank() }
                            ?.let { failedAccumulator.addAll(it) }
                        info.outputData.getString(UploadWorker.KEY_FAILED_MESSAGE)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { failedReasons.add(it) }
                        checkAllFinished()
                    }
                }
            }
        }
    }

    /**
     * 全部批次终态后汇总结果。
     *
     * ⚠️ 这里曾经是"假成功"的最后一环：旧实现无论成败都以「上传完成」开头，
     * （`上传完成，N 个失败：…`），用户看到"上传完成"就以为文件上去了，
     * 于是出现"App 显示成功、云端却没有"的经典误判。
     * 现在按"失败数量占比"改成三种**语义互斥**的文案，失败时不出现"完成/成功"字样。
     */
    private fun checkAllFinished() {
        if (currentWorkIds.any { it !in workStates }) return
        // 裁决入日志：这是"秒成功"唯一能自证的地方。
        // 只要看到 total 与各 id 状态，就能判断是"真的都传完了"还是"Worker 根本没跑"。
        trace(
            "裁决 total=$globalTotal 失败=${failedAccumulator.size} 中断=$abortedFiles " +
                "状态=${workStates.entries.joinToString { "${it.key.toString().take(8)}=${it.value}" }}"
        )
        val failed = failedAccumulator.distinct()
        val aborted = abortedFiles
        // 中断的文件**不能**算进成功数（旧实现就是在这里把 FAILED 当成功，导致假成功）
        val okCount = (globalTotal - failed.size - aborted).coerceAtLeast(0)

        // 失败原因（去重）：只说"部分失败"用户无法判断该怎么办（重新登录？改后缀？走网页上传？）
        val reason = (abortedReasons.distinct() + failedReasons.distinct()).firstOrNull()
        val suffix = reason?.let { "｜原因：$it" } ?: ""

        val parts = mutableListOf<String>()
        if (okCount > 0) parts += "成功 $okCount 个"
        if (aborted > 0) parts += "中断 $aborted 个"
        if (failed.isNotEmpty()) parts += "失败 ${failed.size} 个"
        val detail = parts.joinToString("，").ifEmpty { "没有文件被处理" }

        val (msg, hasFailure) = when {
            // ⚠️ globalTotal == 0 时绝不能报"全部成功"。
            //    "全部上传成功（0 个）"是纯粹的误导：一个文件都没处理，
            //    用户会以为传上去了。理论上 enqueueUpload 已挡住空列表，
            //    但会话恢复路径（init）可能凑出空 total，这里做最后一道兜底。
            globalTotal == 0 ->
                "没有文件被上传（未选择文件或任务未创建）" to true
            failed.isEmpty() && aborted == 0 -> "全部上传成功（$globalTotal 个）" to false
            // 全灭：最容易被误读成成功的场景，必须显眼，且绝不出现"成功"字样
            okCount == 0 -> "上传没成功：$detail$suffix" to true
            else -> "上传结束：$detail$suffix" to true
        }

        _uiState.update {
            it.copy(
                uploading = false,
                message = msg,
                failedFiles = failed,
                hasFailure = hasFailure,
                progress = 0,
                currentFile = ""
            )
        }
        _uploadFinished.tryEmit(Unit)
    }

    /**
     * SAF content:// 拷入缓存 uploads/<uuid>/原名（UUID 子目录隔离同名文件，V3 N4）。
     *
     * ⚠️ 2026-09-12 重要修正：拷完必须**核对字节数**，不能拷完就当成功。
     *
     * 旧实现有三个静默失败路径，全都表现为"秒成功，但云端没有文件"：
     *
     * 1. `openInputStream(uri)` 返回 **null**（源文档已被删除/无权限/来源 App 被回收）
     *    —— 旧代码 `?.use{}` 直接跳过，不抛异常，返回一个 **0 字节**文件；
     * 2. 流读了一点就断（网盘挂载的文档、跨进程 provider 中断）——`copyTo` 本身
     *    不会因为读到的比预期少而报错，返回一个**短了**的文件；
     * 3. 缓存空间不足——写入中途抛异常，被外层 `runCatching` 吞掉，返回 null，
     *    调用方只看到一句笼统的"读取失败"。
     *
     * 前两种都会把空文件/残文件送进上传流程：传一个小 body 出去，服务端几乎
     * 立刻回包，用户看到的就是"秒成功"。
     *
     * @return 拷贝成功的文件；失败返回 null（失败原因见 [lastCopyError]）
     */
    private fun copyUriToCache(uri: Uri): File? {
        lastCopyError = null
        return runCatching {
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
                }
            }.getOrNull() ?: "upload_${System.currentTimeMillis()}"

            // 源文件的标称大小（0 = 提供方未告知，此时只能靠"读到了 0 字节"来判断）
            val declaredSize = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L
                }
            }.getOrDefault(0L)

            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dir = File(context.cacheDir, "uploads/${UUID.randomUUID()}").apply { mkdirs() }
            val out = File(dir, safeName)

            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                // 路径 1：拿不到流。旧实现从这里开始就注定失败且无声。
                lastCopyError =
                    "无法读取所选文件「$safeName」：系统没有给出文件流" +
                        "（文件可能已被删除、移动，或来源 App 已退出）"
                return@runCatching null
            }
            input.use { ins ->
                out.outputStream().use { output -> ins.copyTo(output) }
            }

            val got = out.length()
            // 路径 2a：一个字节都没读到
            if (got == 0L) {
                lastCopyError =
                    "读到的「$safeName」是空文件（0 字节），没有内容可上传" +
                        "（若源文件确实非空，多为来源 App 的授权已失效，请在文件管理器里重新选择）"
                out.delete()
                return@runCatching null
            }
            // 路径 2b：读到的比标称少（断流）——宁可报错也不要把残文件传上去
            if (declaredSize > 0 && got < declaredSize) {
                lastCopyError =
                    "「$safeName」读取不完整：应读 $declaredSize 字节，实际只读到 $got 字节" +
                        "（读写被中断，缓存空间是否充足？）"
                out.delete()
                return@runCatching null
            }
            out
        }.getOrElse { e ->
            // 路径 3：空间不足 / provider 异常
            lastCopyError = "拷贝所选文件失败：${e.javaClass.simpleName} ${e.message}"
            null
        }
    }

    companion object {
        /** logcat 过滤：adb logcat -s CloudBoxUpload */
        private const val TAG = "CloudBoxUpload"

        /**
         * 每个 Worker 处理的文件数上限。
         *
         * 上限来自 WorkManager 的 10 分钟硬限制：批量越大越容易撞线，
         * 越小则 Worker 数量越多、链式时序越复杂。5 是两者的平衡点
         * （详见 enqueueUpload 内的注释）。
         */
        private const val BATCH_SIZE = 5

        /** 时间线最多保留条数 */
        private const val TIMELINE_MAX = 120
    }
}
