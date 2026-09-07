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
    private val shareRepository: ShareRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResolveUiState())
    val uiState: StateFlow<ResolveUiState> = _uiState.asStateFlow()

    fun onInputChange(v: String) = _uiState.update { it.copy(input = v) }

    fun onPasswordChange(v: String) = _uiState.update { it.copy(password = v) }

    /**
     * 解析输入框中的链接（支持多行批量；自动识别 lanzou 系列域名）。
     *
     * 文件夹链接（/bXXXX）会自动展开为目录内全部文件的直链；
     * URL 形态看不出来但页面判定为文件夹时（[ERR_FOLDER_LINK]）同样自动改走目录流程。
     */
    fun resolve() {
        val s = _uiState.value
        val urls = s.input.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { com.cloudbox.app.common.DomainUtils.isShareUrl(it) }
        if (urls.isEmpty()) {
            _uiState.update { it.copy(message = "未识别到蓝奏云分享链接") }
            return
        }
        if (s.resolving) return
        _uiState.update { it.copy(resolving = true, results = emptyList(), progress = "开始解析…") }
        viewModelScope.launch {
            val out = mutableListOf<ResolveItem>()
            urls.forEachIndexed { index, url ->
                _uiState.update {
                    it.copy(progress = "解析第 ${index + 1}/${urls.size} 条…")
                }
                if (isFolderShareUrl(url)) {
                    // URL 形态已表明是文件夹：先按目录展开，一条都没拿到再退回单文件流程
                    val expanded = expandFolder(url, s.password)
                    if (expanded.any { it.link != null }) out.addAll(expanded)
                    else out.addAll(resolveSingle(url, s.password))
                } else {
                    val single = resolveSingle(url, s.password)
                    // 单文件流程报"页面判定为文件夹" → 自动改走目录展开
                    if (lastWasFolderLink) out.addAll(expandFolder(url, s.password))
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
