package com.cloudbox.app.feature.resolve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.common.ApiError
import com.cloudbox.app.core.domain.model.DirectLink
import com.cloudbox.app.core.domain.repository.DirectLinkRepository
import com.cloudbox.app.core.domain.repository.DownloadRepository
import com.cloudbox.app.core.domain.repository.ERR_FOLDER_LINK
import com.cloudbox.app.core.domain.repository.FileRepository
import com.cloudbox.app.core.domain.repository.ShareRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 解析结果项 */
data class ResolveItem(
    /** LazyColumn 的 key：目录展开会产生多条同 shareUrl 的项，必须唯一 */
    val key: String,
    val shareUrl: String,
    val link: DirectLink?,
    val error: String? = null
)

/** 解析历史条目（V56，对齐原版 open_link_history：url + 摘要 + 时间） */
data class ResolveHistoryEntry(
    val url: String,
    val summary: String,
    val time: Long
)

/** 解析页 UI 状态 */
data class ResolveUiState(
    val input: String = "",
    val password: String = "",
    val resolving: Boolean = false,
    val results: List<ResolveItem> = emptyList(),
    /** 耗时操作的进度提示（文件夹解析要逐文件请求，可能几十秒） */
    val progress: String? = null,
    val message: String? = null,
    /** V56 解析历史（最新在前；开关关=空列表） */
    val history: List<ResolveHistoryEntry> = emptyList()
)

@HiltViewModel
class ResolveViewModel @Inject constructor(
    private val directLinkRepository: DirectLinkRepository,
    private val downloadRepository: DownloadRepository,
    private val fileRepository: FileRepository,
    private val shareRepository: ShareRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    /** V53 去 private：Screen 侧按需读取（第三方下载器开关+包名） */
    val settingsStore: com.cloudbox.app.core.data.local.datastore.SettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResolveUiState())
    val uiState: StateFlow<ResolveUiState> = _uiState.asStateFlow()

    init {
        // V56：装载解析历史（原版 open_link_history 默认开）
        viewModelScope.launch { loadHistory() }
    }

    private suspend fun loadHistory() {
        val on = settingsStore.openLinkHistory.first()
        if (!on) return
        val list = parseHistoryJson(settingsStore.resolveHistoryJson.first())
        _uiState.update { it.copy(history = list) }
    }

    /** 解析成功后记录历史（同 url 挪到最前，上限 50 条） */
    private suspend fun recordHistory(urls: List<String>, out: List<ResolveItem>) {
        if (!settingsStore.openLinkHistory.first()) return
        val old = parseHistoryJson(settingsStore.resolveHistoryJson.first())
        val now = System.currentTimeMillis()
        val entries = urls.map { u ->
            val name = out.firstOrNull { it.shareUrl == u && it.link != null }?.link?.fileName
                ?.ifBlank { null } ?: out.firstOrNull { it.shareUrl == u }?.let { "（无结果）" } ?: u
            ResolveHistoryEntry(u, name, now)
        }
        val merged = (entries + old.filter { e -> e.url !in urls }).take(50)
        settingsStore.setResolveHistoryJson(writeHistoryJson(merged))
        _uiState.update { it.copy(history = merged) }
    }

    /** V56：删除单条历史 */
    fun removeHistory(url: String) {
        viewModelScope.launch {
            val list = parseHistoryJson(settingsStore.resolveHistoryJson.first())
                .filterNot { it.url == url }
            settingsStore.setResolveHistoryJson(writeHistoryJson(list))
            _uiState.update { it.copy(history = if (settingsStore.openLinkHistory.first()) list else emptyList()) }
        }
    }

    /** V56：清空历史 */
    fun clearHistory() {
        viewModelScope.launch {
            settingsStore.setResolveHistoryJson("[]")
            _uiState.update { it.copy(history = emptyList()) }
        }
    }

    /** V56：从历史回填（点击条目=填入输入框并直接解析） */
    fun useHistory(entry: ResolveHistoryEntry) {
        _uiState.update { it.copy(input = entry.url) }
        resolve()
    }

    private fun parseHistoryJson(json: String): List<ResolveHistoryEntry> = runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ResolveHistoryEntry(
                url = o.optString("url"),
                summary = o.optString("summary"),
                time = o.optLong("time")
            )
        }.filter { it.url.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun writeHistoryJson(list: List<ResolveHistoryEntry>): String = runCatching {
        val arr = org.json.JSONArray()
        list.forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("url", e.url); put("summary", e.summary); put("time", e.time)
            })
        }
        arr.toString()
    }.getOrDefault("[]")

    fun onInputChange(v: String) = _uiState.update { it.copy(input = v) }

    fun onPasswordChange(v: String) = _uiState.update { it.copy(password = v) }

    /**
     * 解析输入框中的链接（支持多行批量；自动识别 lanzou 系列域名）。
     *
     * 输入**不要求是干净 URL** —— 微信/QQ/抖音分享出来的整段文本
     * （"蓝奏云盘 https://xxx 提取码：abcd"）可以直接粘贴：
     * 这里会先把 URL 抠出来，再顺手把提取码填进密码框。
     *
     * 文件夹链接（/bXXXX）会自动展开为目录内全部文件的直链；
     * URL 形态看不出来但页面判定为文件夹时（[ERR_FOLDER_LINK]）同样自动改走目录流程。
     */
    fun resolve() {
        val s = _uiState.value
        // ⚠️ 这里必须走 extractShareUrls 而不是 isShareUrl 直接过滤：
        //    旧实现拿整段文本喂 java.net.URI，只要带中文/空格就抛异常被判为
        //    "不是分享链接"，导致粘贴分享文案时永远报"未识别到蓝奏云分享链接"。
        val urls = com.cloudbox.app.common.DomainUtils.extractShareUrls(s.input)
        if (urls.isEmpty()) {
            _uiState.update { it.copy(message = "未识别到蓝奏云分享链接") }
            return
        }
        // 分享文案里带的提取码自动填进密码框（用户没自己填过才填，不覆盖手输值）
        val autoPwd = if (s.password.isBlank()) {
            com.cloudbox.app.common.DomainUtils.extractPassword(s.input)
        } else null
        val pwd = autoPwd ?: s.password
        if (s.resolving) return
        _uiState.update {
            it.copy(
                resolving = true,
                results = emptyList(),
                progress = "开始解析…",
                password = pwd,
                input = urls.joinToString("\n")
            )
        }
        viewModelScope.launch {
            val out = mutableListOf<ResolveItem>()
            urls.forEachIndexed { index, url ->
                _uiState.update {
                    it.copy(progress = "解析第 ${index + 1}/${urls.size} 条…")
                }
                if (isFolderShareUrl(url)) {
                    // URL 形态已表明是文件夹：先按目录展开，一条都没拿到再退回单文件流程
                    val expanded = expandFolder(url, pwd)
                    if (expanded.any { it.link != null }) out.addAll(expanded)
                    else out.addAll(resolveSingle(url, pwd))
                } else {
                    val single = resolveSingle(url, pwd)
                    // 单文件流程报"页面判定为文件夹" → 自动改走目录展开
                    if (lastWasFolderLink) out.addAll(expandFolder(url, pwd))
                    else out.addAll(single)
                }
            }
            _uiState.update { it.copy(resolving = false, progress = null, results = out) }
            // V56：解析完成记录历史（无论成败都记——原版语义是"打开过的链接"）
            runCatching { recordHistory(urls, out) }
        }
    }

    /**
     * 单条链接解析。返回列表是为了与 [expandFolder] 统一形状
     * （一条文件夹链接会展开成 N 条结果）。
     */
    private suspend fun resolveSingle(url: String, pwd: String): List<ResolveItem> {
        val r = directLinkRepository.resolve(url, pwd)
        val ok = r.getOrNull()
        if (ok != null) {
            lastWasFolderLink = false
            return listOf(ResolveItem(key = url, shareUrl = url, link = ok))
        }
        val e = r.exceptionOrNull() ?: return emptyList()
        // 页面内容判定为文件夹（比 URL 前缀准）→ 标记，由调用方改走目录展开
        lastWasFolderLink = (e as? ApiError.Business)?.code == ERR_FOLDER_LINK
        return listOf(ResolveItem(key = url, shareUrl = url, link = null, error = e.message))
    }

    /** 文件夹链接 → 展开为目录内全部文件的直链 */
    private suspend fun expandFolder(url: String, pwd: String): List<ResolveItem> {
        _uiState.update { it.copy(progress = "正在展开文件夹…") }
        val r = directLinkRepository.resolveFolder(url, pwd)
        return r.fold(
            onSuccess = { res ->
                val items = mutableListOf<ResolveItem>()
                res.links.forEachIndexed { i, link ->
                    items.add(ResolveItem(key = "$url#$i", shareUrl = url, link = link))
                }
                if (res.failedCount > 0) {
                    items.add(
                        ResolveItem(
                            key = "$url#failed",
                            shareUrl = url,
                            link = null,
                            error = "目录内 ${res.failedCount}/${res.totalCount} 个文件解析失败（多为风控限流，可稍后重试）"
                        )
                    )
                }
                if (res.links.isEmpty()) {
                    items.add(
                        ResolveItem(
                            key = "$url#empty",
                            shareUrl = url,
                            link = null,
                            error = "目录为空，或提取码错误"
                        )
                    )
                }
                items
            },
            onFailure = { e ->
                listOf(ResolveItem(key = url, shareUrl = url, link = null, error = e.message))
            }
        )
    }

    /**
     * 上一次 [resolveSingle] 是否因"页面判定为文件夹"而失败。
     * 用字段传递而不是把状态塞进异常，避免污染错误类型。
     */
    private var lastWasFolderLink = false

    /**
     * 下载解析结果。
     *
     * V47（2026-10-04）：**不再直接用解析时缓存的直链**——解析结果可能已过期
     * （直链时效约 30 分钟）或本身就是带挑战的原始链（DownloadManager 不会解
     * acw 挑战，会把挑战页当文件存成 4.7K 假文件）。点下载时用 force 重解析，
     * 拿 finalize 过的 CDN 直链再入队。重解析失败回落旧链（保底能下载）。
     */
    fun download(item: ResolveItem) {
        val oldLink = item.link ?: return
        viewModelScope.launch {
            // 移动网络提醒：流量是用户的钱，默认先问一句（设置页可关）
            if (settingsStore.warnMobileNetwork.first() &&
                com.cloudbox.app.common.NetworkUtil.isOnMobileData(context)
            ) {
                _uiState.update {
                    it.copy(message = "当前是移动网络，已开始下载（可在设置页关闭此提醒）")
                }
            }
            val pwd = _uiState.value.password
            val link = directLinkRepository.resolve(item.shareUrl, pwd, force = true)
                .getOrNull() ?: oldLink

            // V56 对齐蓝云三分支（ty_core.lua「下载」）：开而未配置 →
            // ACTION_VIEW 系统选择器（原版「浏览器打开」，每次询问——
            // 设置帮助文案的出处）；FAILED 提示不回落（防双轨）
            if (settingsStore.useThirdPartyDownloader.first()) {
                when (com.cloudbox.app.common.ThirdPartyDownloader.handOff(
                    link.url, link.fileName, context,
                    settingsStore.customDownloaderPack.first(),
                    settingsStore.customDownloaderActivity.first()
                )) {
                    com.cloudbox.app.common.HandOffResult.SENT -> {
                        _uiState.update { it.copy(message = "已交给第三方下载器：${link.fileName}") }
                        return@launch
                    }
                    com.cloudbox.app.common.HandOffResult.CHOOSER -> {
                        // 系统选择器已弹出（原版此分支无提示）
                        return@launch
                    }
                    com.cloudbox.app.common.HandOffResult.FAILED -> {
                        _uiState.update { it.copy(message = "第三方下载器打开失败，请在设置中重新配置") }
                        return@launch
                    }
                }
            }
            val uid = fileRepository.currentUid() ?: ""
            // V48：enqueue 带预检，可能抛 ApiError——包 runCatching 防协程崩溃
            runCatching {
                downloadRepository.enqueue(
                    url = link.url,
                    fileName = link.fileName,
                    referer = link.referer,
                    mimeType = if (link.fileName.endsWith(".apk", true)) "application/vnd.android.package-archive" else null,
                    accountUid = uid
                )
            }.onSuccess {
                _uiState.update { it.copy(message = "已加入下载队列：${link.fileName}") }
            }.onFailure { e ->
                _uiState.update { it.copy(message = "下载失败：${e.message}") }
            }
        }
    }

    /**
     * 收藏分享链接。
     *
     * V30（对齐 v1.3.4.9）：把「是不是文件夹」与提取码一并存进收藏。
     * 原版用 `open_link_history[i].type` / `.pass` 区分，并且**只对 folder 型
     * 收藏做更新检查**（favorites.lua:1126）—— 不记这两个字段的话，
     * 收藏夹页的「检查收藏文件夹更新」永远是空的。
     *
     * 类型判定用 [isFolderShareUrl] 初筛；它可能误判（短链形态多变），
     * 但收藏夹里的类型只影响「要不要给它做更新检查」，误判的代价是可接受且可纠正的
     * （用户在解析页对同一链接重新收藏一次即可覆盖）。
     */
    fun favorite(url: String, name: String) {
        viewModelScope.launch {
            val kind = if (isFolderShareUrl(url)) "folder" else "file"
            val pwd = _uiState.value.password
            shareRepository.addFavorite(url, name)
            shareRepository.correctKind(url, kind, pwd)
            _uiState.update { it.copy(message = "已收藏") }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    /** 由 UI 层触发一条一次性提示 */
    fun showMessage(text: String) = _uiState.update { it.copy(message = text) }

    companion object {
        /**
         * 文件夹分享链接判定：路径最后一段以 b 开头（实测 /b01tpeg7i、/b0auv0qf）。
         * 单文件是 /iXXXX。这只是**初筛**——页面内容判定（isFolderPage）才是准的，
         * 所以误判时 [resolve] 里有"目录流程失败则退回单文件"的兜底。
         */
        private val FOLDER_PATH = Regex("""/b[0-9a-zA-Z]+/?$""")
        fun isFolderShareUrl(url: String): Boolean = FOLDER_PATH.containsMatchIn(url.trim())
    }
}
