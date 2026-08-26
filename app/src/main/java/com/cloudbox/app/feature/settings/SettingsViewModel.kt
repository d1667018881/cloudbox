package com.cloudbox.app.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.domain.model.AccountInfo
import com.cloudbox.app.core.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val message: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val authRepository: AuthRepository
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

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}
