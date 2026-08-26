package com.cloudbox.app.feature.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.LanzouDomainConfig
import com.cloudbox.app.core.domain.repository.DomainLatency
import com.cloudbox.app.core.domain.repository.DomainRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 域名配置页 UI 状态 */
data class DomainUiState(
    val config: LanzouDomainConfig = LanzouDomainConfig.DEFAULT,
    val remoteUrl: String = "",
    val testing: Boolean = false,
    val latencies: List<DomainLatency> = emptyList(),
    val message: String? = null,
    val saved: Boolean = false
)

@HiltViewModel
class DomainConfigViewModel @Inject constructor(
    private val repository: DomainRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DomainUiState())
    val uiState: StateFlow<DomainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // 订阅当前生效配置（本地覆盖 + 远程合并结果）
            repository.domainConfig.collect { config ->
                _uiState.update { it.copy(config = config) }
            }
        }
    }

    fun updateField(field: DomainField, value: String) {
        _uiState.update {
            val c = it.config
            val updated = when (field) {
                DomainField.LOGIN -> c.copy(loginEntry = value)
                DomainField.DISK -> c.copy(diskMain = value)
                DomainField.SHARE -> c.copy(shareBase = value)
                DomainField.UPLOAD -> c.copy(uploadServer = value)
            }
            it.copy(config = updated, saved = false)
        }
    }

    fun updateRemoteUrl(url: String) = _uiState.update { it.copy(remoteUrl = url) }

    /** 保存手动覆盖配置 */
    fun saveLocal() {
        viewModelScope.launch {
            repository.saveLocal(_uiState.value.config)
            _uiState.update { it.copy(saved = true, message = "已保存本地配置") }
        }
    }

    /** 从远程 URL 拉取并应用 */
    fun fetchRemote() {
        val url = _uiState.value.remoteUrl.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(message = "请先填写远程配置 URL") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(message = "正在拉取远程配置…") }
            val result = repository.fetchAndApplyRemote(url)
            _uiState.update {
                it.copy(message = result.fold(
                    onSuccess = { "远程配置已应用" },
                    onFailure = { e -> "拉取失败：${e.message}" }
                ))
            }
        }
    }

    /** 并发 HEAD 测连通性（需求规格：测试连通性按钮） */
    fun testConnectivity() {
        viewModelScope.launch {
            _uiState.update { it.copy(testing = true, latencies = emptyList(), message = null) }
            val result = repository.testConnectivity(_uiState.value.config)
            _uiState.update { it.copy(testing = false, latencies = result) }
        }
    }

    fun resetLocal() {
        viewModelScope.launch {
            repository.resetLocal()
            _uiState.update { it.copy(saved = true, message = "已重置为默认配置") }
        }
    }
}

enum class DomainField { LOGIN, DISK, SHARE, UPLOAD }
