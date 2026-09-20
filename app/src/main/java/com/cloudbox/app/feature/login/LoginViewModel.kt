package com.cloudbox.app.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.core.domain.repository.LoginResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 登录页 UI 状态 */
data class LoginUiState(
    val uid: String = "",
    val pwd: String = "",
    val rememberPwd: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val alreadyLoggedIn: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** 登录成功回调（由 UI 层注入，避免 ViewModel 持有导航依赖） */
    var onLoginSuccess: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            // 持续观察当前账号：App 启动时 ensureSession 可能异步静默重登，
            // 用 collect 而不是 first() 才能在其完成后自动跳转主页。
            authRepository.currentAccount.collect { account ->
                if (account != null) {
                    // ⚠️ 账号仍然存在、但**凭证已被服务端拒绝**（典型场景：在官网改了密码，
                    //    而 App 的 Cookie 还没到期）。此时绝不能当"已登录"跳主页 ——
                    //    旧实现就是这么做的，用户看到的现象正是"闪一下登录页又进去了"，
                    //    而进去之后任何操作都会失败，用户完全无法自救。
                    //
                    //    现在：把账号名预填进输入框（减少重复输入），把服务端的原话
                    //    显示出来（"没有用户"/"密码错误"），并把焦点留在登录页。
                    val stale = account.staleReason
                    if (stale.isNullOrBlank()) {
                        _uiState.update { it.copy(alreadyLoggedIn = true) }
                        onLoginSuccess?.invoke()
                    } else {
                        _uiState.update {
                            it.copy(
                                uid = it.uid.ifBlank { account.uid },
                                alreadyLoggedIn = false,
                                error = "账号「${account.uid}」的登录状态已失效：$stale。请重新输入密码登录。"
                            )
                        }
                        // 顺带清掉那个已失效的槽位：它的 Cookie 已无意义，
                        // 留着只会让"当前账号"指向一个不可用的身份。
                        authRepository.logout(account.uid)
                    }
                }
            }
        }
    }

    fun onUidChange(value: String) = _uiState.update { it.copy(uid = value, error = null) }

    fun onPwdChange(value: String) = _uiState.update { it.copy(pwd = value, error = null) }

    fun onRememberChange(value: Boolean) = _uiState.update { it.copy(rememberPwd = value) }

    fun login() {
        val state = _uiState.value
        if (state.uid.isBlank() || state.pwd.isBlank()) {
            _uiState.update { it.copy(error = "请输入账号和密码") }
            return
        }
        if (state.loading) return
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.login(state.uid.trim(), state.pwd, state.rememberPwd)
            _uiState.update { it.copy(loading = false) }
            when (result) {
                is LoginResult.Success -> onLoginSuccess?.invoke()
                is LoginResult.Failure -> _uiState.update { it.copy(error = result.reason) }
            }
        }
    }

    /** 从剪贴板导入 phpdisk_info Cookie 串 */
    fun importCookie(cookieText: String) {
        val uid = _uiState.value.uid.trim()
        if (uid.isBlank()) {
            _uiState.update { it.copy(error = "请先填写账号名（作为 Cookie 槽位名）") }
            return
        }
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.importCookie(uid, cookieText)
            _uiState.update { it.copy(loading = false) }
            when (result) {
                is LoginResult.Success -> onLoginSuccess?.invoke()
                is LoginResult.Failure -> _uiState.update { it.copy(error = result.reason) }
            }
        }
    }
}
