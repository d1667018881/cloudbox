package com.cloudbox.app.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.UserProfile
import com.cloudbox.app.core.domain.repository.ProfileRepository
import com.cloudbox.app.core.domain.repository.ProfileResult
import com.cloudbox.app.core.domain.repository.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountUiState(
    val loading: Boolean = false,
    val profile: UserProfile? = null,
    val error: String? = null,
    /** 某个写操作提交中（用于禁用对话框按钮，防重复提交） */
    val submitting: Boolean = false,
    val message: String? = null
)

/**
 * 账号面板 ViewModel。
 *
 * 两块能力：
 * 1. **读**：[UserProfileRepository.fetchProfile] 拉取账户概览（原版「获取用户信息」）；
 * 2. **写**：复用 [ProfileRepository] 的四个接口（task=7/8/10/15）。
 *
 * ⚠️ 这四个写接口此前**全仓库没有任何 UI 调用点**（对照分析发现的缺口）——
 * 接口看着齐全，用户却无处可点。本页面把它们落地。
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val userProfileRepository: UserProfileRepository,
    private val profileRepository: ProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_uiState.value.loading) return
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            userProfileRepository.fetchProfile()
                .onSuccess { p ->
                    _uiState.update { it.copy(loading = false, profile = p, error = null) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
                }
        }
    }

    fun changePassword(oldPwd: String, newPwd: String) = submit {
        profileRepository.changePassword(oldPwd, newPwd)
    }

    fun setExternalLink(title: String, summary: String) = submit {
        profileRepository.setExternalLink(title, summary)
    }

    fun setPublisher(show: Boolean, nickname: String) = submit {
        profileRepository.setPublisher(show, nickname)
    }

    fun setPersonalLinkCode(enableCode: Boolean, code: String) = submit {
        profileRepository.setPersonalLinkCode(enableCode, code)
    }

    /** 统一的写操作收口：防重复提交 → 调接口 → 成功则刷新概览并提示 */
    private fun submit(block: suspend () -> ProfileResult) {
        if (_uiState.value.submitting) return
        _uiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            when (val r = block()) {
                is ProfileResult.Success -> {
                    _uiState.update { it.copy(submitting = false, message = r.info ?: "已保存") }
                    refresh()
                }
                is ProfileResult.Failure ->
                    _uiState.update { it.copy(submitting = false, message = r.reason) }
            }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}
