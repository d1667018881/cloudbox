package com.cloudbox.app.feature.resolve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.DirectLink
import com.cloudbox.app.core.domain.repository.DirectLinkRepository
import com.cloudbox.app.core.domain.repository.DownloadRepository
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

    /** 解析输入框中的链接（支持多行批量；自动识别 lanzou 系列域名） */
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
        _uiState.update { it.copy(resolving = true, results = emptyList()) }
        viewModelScope.launch {
            val results = urls.map { url ->
                val r = directLinkRepository.resolve(url, s.password)
                r.fold(
                    onSuccess = { ResolveItem(url, it) },
                    onFailure = { ResolveItem(url, null, it.message) }
                )
            }
            _uiState.update { it.copy(resolving = false, results = results) }
        }
    }

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
}
