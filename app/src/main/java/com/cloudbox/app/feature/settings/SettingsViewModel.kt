package com.cloudbox.app.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.domain.model.AccountInfo
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.core.domain.repository.ProfileRepository
import com.cloudbox.app.core.domain.repository.ProfileResult
import com.cloudbox.app.core.domain.repository.UploadProbeResult
import com.cloudbox.app.core.domain.repository.UploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 设置页 UI 状态 */
data class SettingsUiState(
    val userAgent: String = AppConstants.DESKTOP_UA,
    val suffixSpoof: Boolean = true,
    val thirdPartyResolver: String = "",
    val darkMode: String = "system",
    val accounts: List<AccountInfo> = emptyList(),
    val currentUid: String? = null,
    val cookieExported: String? = null,
    /** 上传自检进行中 */
    val probing: Boolean = false,
    /** 上传自检结果（含服务端原始回包），非空时 UI 弹出详情 */
    val probeResult: UploadProbeResult? = null,
    val message: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val uploadRepository: UploadRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val ua = settingsStore.userAgent.first()
            val spoof = settingsStore.suffixSpoofEnabled.first()
            val resolver = settingsStore.thirdPartyResolverUrl.first()
            val dark = settingsStore.darkMode.first()
            _uiState.update {
                it.copy(
                    userAgent = ua, suffixSpoof = spoof,
                    thirdPartyResolver = resolver, darkMode = dark
                )
            }
        }
        viewModelScope.launch {
            authRepository.currentAccount.collect { acc ->
                _uiState.update { it.copy(currentUid = acc?.uid) }
            }
        }
        loadAccounts()
    }

    fun loadAccounts() {
        viewModelScope.launch {
            val accounts = authRepository.allAccounts()
            _uiState.update { it.copy(accounts = accounts) }
        }
    }

    fun saveUserAgent(ua: String) {
        viewModelScope.launch {
            settingsStore.setUserAgent(ua.trim().ifEmpty { AppConstants.DESKTOP_UA })
            _uiState.update { it.copy(userAgent = ua.trim().ifEmpty { AppConstants.DESKTOP_UA }, message = "UA 已保存（立即生效）") }
        }
    }

    fun saveSuffixSpoof(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setSuffixSpoof(enabled)
            _uiState.update { it.copy(suffixSpoof = enabled) }
        }
    }

    fun saveThirdPartyResolver(url: String) {
        viewModelScope.launch {
            settingsStore.setThirdPartyResolver(url.trim())
            _uiState.update { it.copy(thirdPartyResolver = url.trim()) }
        }
    }

    fun saveDarkMode(mode: String) {
        viewModelScope.launch {
            settingsStore.setDarkMode(mode)
            _uiState.update { it.copy(darkMode = mode) }
        }
    }

    /** 一键切换账号 */
    fun switchAccount(uid: String) {
        viewModelScope.launch {
            val ok = authRepository.switchAccount(uid)
            _uiState.update { it.copy(message = if (ok) "已切换到 $uid" else "切换失败") }
            loadAccounts()
        }
    }

    /** 删除账号（含 Cookie 槽位） */
    fun removeAccount(uid: String) {
        viewModelScope.launch {
            authRepository.logout(uid)
            loadAccounts()
        }
    }

    /** 导出当前账号 Cookie 到剪贴板 */
    fun exportCookies() {
        viewModelScope.launch {
            val uid = _uiState.value.currentUid ?: return@launch
            val cookies = authRepository.exportCookies(uid)
            if (cookies == null) {
                _uiState.update { it.copy(message = "当前账号无 Cookie 可导出") }
            } else {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("cookie", cookies))
                _uiState.update { it.copy(message = "Cookie 已复制到剪贴板") }
            }
        }
    }

    /** 从剪贴板恢复 Cookie 到当前账号 */
    fun restoreCookiesFromClipboard() {
        viewModelScope.launch {
            val uid = _uiState.value.currentUid ?: return@launch
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (clip.isNullOrBlank()) {
                _uiState.update { it.copy(message = "剪贴板为空") }
                return@launch
            }
            val ok = authRepository.restoreCookies(uid, clip)
            _uiState.update { it.copy(message = if (ok) "Cookie 已恢复" else "恢复失败") }
        }
    }

    // ==================== 上传通道自检 ====================

    /**
     * 跑一次探针上传：往根目录传一个 40 字节 txt，把服务端原始回包展示出来。
     *
     * 用途：上传"显示成功却没上去"时，用它一眼看出是没登录（zt=9）、
     * 参数不对（zt=1 但 text 不是数组），还是域名/端点不对（404、HTML 页面）。
     */
    fun runUploadProbe() {
        viewModelScope.launch {
            _uiState.update { it.copy(probing = true, probeResult = null) }
            val r = uploadRepository.probeUpload(-1L)
            _uiState.update { it.copy(probing = false, probeResult = r) }
        }
    }

    /**
     * 用**用户选的真实文件**跑自检——排障主力。
     *
     * 内置探针只有 40 字节，它能过只说明链路通；
     * 真正失败的文件可能是太大（超时）、格式受限、或文件名编码有问题，
     * 这些只有拿原文件测才会暴露。
     */
    fun runUploadProbeWith(uri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(probing = true, probeResult = null) }
            val r = withContext(Dispatchers.IO) {
                val f = copyUriToCache(uri)
                if (f == null) {
                    UploadProbeResult(
                        httpCode = -1,
                        requestUrl = "未发出",
                        rawBody = "无法读取所选文件（把它拷进 App 缓存失败）",
                        hasCredential = false
                    )
                } else {
                    uploadRepository.probeUploadWith(f, -1L)
                }
            }
            _uiState.update { it.copy(probing = false, probeResult = r) }
        }
    }

    /** SAF uri → 缓存文件，保留原始文件名（与 UploadViewModel 的做法一致） */
    private fun copyUriToCache(uri: android.net.Uri): java.io.File? = runCatching {
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
            }
        }.getOrNull() ?: "probe_${System.currentTimeMillis()}"
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dir = java.io.File(context.cacheDir, "probe").apply { mkdirs() }
        val out = java.io.File(dir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        out.takeIf { it.exists() && it.length() > 0 }
    }.getOrNull()

    fun dismissProbe() = _uiState.update { it.copy(probeResult = null) }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    // ==================== 账号中心设置（task=7/8/10/15） ====================

    /** 统一渲染 ProfileResult：成功弹服务端提示（没有就给默认文案），失败弹原因 */
    private fun emitProfileResult(result: ProfileResult, defaultOk: String) {
        _uiState.update {
            it.copy(
                message = when (result) {
                    is ProfileResult.Success -> result.info ?: defaultOk
                    is ProfileResult.Failure -> result.reason
                }
            )
        }
    }

    /** 个人分享链访问码 */
    fun setPersonalLinkCode(enableCode: Boolean, code: String) {
        viewModelScope.launch {
            emitProfileResult(
                profileRepository.setPersonalLinkCode(enableCode, code),
                if (enableCode) "已启用访问码" else "已关闭访问码"
            )
        }
    }

    /** 修改登录密码 */
    fun changePassword(oldPwd: String, newPwd: String) {
        viewModelScope.launch {
            emitProfileResult(
                profileRepository.changePassword(oldPwd, newPwd), "密码已修改，请用新密码重新登录"
            )
        }
    }

    /** 外链（个人主页）标题与简介 */
    fun setExternalLink(title: String, summary: String) {
        viewModelScope.launch {
            emitProfileResult(
                profileRepository.setExternalLink(title, summary), "外链信息已更新"
            )
        }
    }

    /** 是否显示发布者 */
    fun setPublisher(show: Boolean, nickname: String) {
        viewModelScope.launch {
            emitProfileResult(
                profileRepository.setPublisher(show, nickname),
                if (show) "已开启显示发布者" else "已关闭显示发布者"
            )
        }
    }
}
