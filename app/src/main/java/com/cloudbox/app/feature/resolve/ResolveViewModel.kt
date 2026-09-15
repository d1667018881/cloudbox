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

/** 解析页 UI 状态 */
data class ResolveUiState(
    val input: String = "",
    val password: String = "",
    val resolving: Boolean = false,
    val results: List<ResolveItem> = emptyList(),
    /** 耗时操作的进度提示（文件夹解析要逐文件请求，可能几十秒） */
    val progress: String? = null,
    val message: String? = null
)

@HiltViewModel
class ResolveViewModel @Inject constructor(
    private val directLinkRepository: DirectLinkRepository,
    private val downloadRepository: DownloadRepository,
    private val fileRepository: FileRepository,
    private val shareRepository: ShareRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val settingsStore: com.cloudbox.app.core.data.local.datastore.SettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResolveUiState())
    val uiState: StateFlow<ResolveUiState> = _uiState.asStateFlow()

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

    /** 下载解析结果 */
    fun download(item: ResolveItem) {
        val link = item.link ?: return
        viewModelScope.launch {
            // 移动网络提醒：流量是用户的钱，默认先问一句（设置页可关）
            if (settingsStore.warnMobileNetwork.first() &&
                com.cloudbox.app.common.NetworkUtil.isOnMobileData(context)
            ) {
                _uiState.update {
                    it.copy(message = "当前是移动网络，已开始下载（可在设置页关闭此提醒）")
                }
            }
            val uid = fileRepository.currentUid() ?: ""
            downloadRepository.enqueue(
                url = link.url,
                fileName = link.fileName,
                referer = link.referer,
                mimeType = if (link.fileName.endsWith(".apk", true)) "application/vnd.android.package-archive" else null,
                accountUid = uid
            )
            _uiState.update { it.copy(message = "已加入下载队列：${link.fileName}") }
        }
    }

    /** 收藏分享链接 */
    fun favorite(url: String, name: String) {
        viewModelScope.launch {
            shareRepository.addFavorite(url, name)
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
