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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val profileRepository: ProfileRepository,
    private val authRepository: com.cloudbox.app.core.domain.repository.AuthRepository,
    /** V47：网页版/个人中心/变更手机号/注销都跳官方网页（带登录态） */
    private val domainInterceptor: com.cloudbox.app.core.data.remote.LanzouDomainInterceptor
) : ViewModel() {

    /** 账户概览页（myfile.php?item=1&v2，与 getUserProfile 同页——已验证存在） */
    private fun diskBase() = domainInterceptor.snapshot().diskMain.trimEnd('/')

    fun accountCenterUrl() = "${diskBase()}/myfile.php?item=1&v2"

    /** 网页版文件管理（与 WebViewUploadActivity 同款 URL 形态——已验证存在） */
    fun webDiskUrl() = "${diskBase()}/mydisk.php?item=files&action=index"

    /**
     * 变更手机号 / 注销账户：必须短信验证，App 侧做不了（ProfileRepositoryImpl
     * 对 task=43 的结论），打开账户概览页（页内有手机与安全设置的入口）。
     */
    fun accountSecurityUrl() = accountCenterUrl()

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

    // ==================== V52：账号切换（原抽屉「切换账户」并入，TA 2026-10-09 指示） ====================

    private val _accounts = MutableStateFlow<List<com.cloudbox.app.core.domain.model.AccountInfo>>(emptyList())

    /** 已保存账号列表（切换区块数据源） */
    val accounts: StateFlow<List<com.cloudbox.app.core.domain.model.AccountInfo>> = _accounts.asStateFlow()

    private val _switched = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** 切换成功（uid）——Screen 消费后回主页并刷新（主页 key(uid) 重建文件列表） */
    val switched = _switched

    fun loadAccounts() {
        viewModelScope.launch {
            runCatching { _accounts.value = authRepository.allAccounts() }
        }
    }

    /** 切换账号：cookie 直切（对齐蓝云「选择已有账号登录」） */
    fun switchAccount(uid: String) {
        viewModelScope.launch {
            if (authRepository.switchAccount(uid)) {
                _switched.tryEmit(uid)
            } else {
                postMessage("切换失败（账号「$uid」的 Cookie 可能已失效）")
            }
        }
    }

    /** 删除账号槽位（含 Cookie） */
    fun removeAccount(uid: String) {
        viewModelScope.launch {
            authRepository.logout(uid)
            _accounts.value = authRepository.allAccounts()
        }
    }

    /** Screen 侧动作（如复制分享链）的回执提示 */
    fun postMessage(text: String) = _uiState.update { it.copy(message = text) }

    /**
     * 退出登录（V47：账号面板对齐蓝云截图的 9 项之一；抽屉里的退出入口保留）。
     *
     * 与 MainScreen.doLogout 同款时序：**先真正注销（清 Cookie 槽位）再发导航事件**，
     * 顺序反了会出现"登录页闪一下又弹回主页"（currentAccount 还没清空，
     * 见 AuthRepositoryImpl.logout 注释）。完成后经 [loggedOut] 通知 Screen 切页。
     */
    private val _loggedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedOut: kotlinx.coroutines.flow.SharedFlow<Unit> = _loggedOut

    fun logout() {
        viewModelScope.launch {
            authRepository.currentAccount.first()?.uid?.let { authRepository.logout(it) }
            _loggedOut.emit(Unit)
        }
    }
}
