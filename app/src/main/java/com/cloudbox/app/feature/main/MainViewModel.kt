package com.cloudbox.app.feature.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.common.ClipboardLinkWatcher
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.core.domain.repository.DirectLinkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.launch

/** 主界面 ViewModel：聚合剪贴板监听器 + 认证仓库 + 直链解析（弹窗"获取直链"用） */
@HiltViewModel
class MainViewModel @Inject constructor(
    val clipboardWatcher: ClipboardLinkWatcher,
    val authRepository: AuthRepository,
    private val directLinkRepository: DirectLinkRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /** 弹窗"获取直链"：不跳解析页，直接解析当前分享链接并把直链复制到剪贴板。
     *  onResult(success, message)：UI 侧用于 Toast 提示；成功后调用方应 dismiss 弹窗。 */
    fun resolveDirectLink(url: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val r = directLinkRepository.resolve(url, "")
            r.fold(
                onSuccess = { link ->
                    copyToClipboard(link.url)
                    onResult(true, "直链已复制：${link.fileName}")
                },
                onFailure = { onResult(false, it.message ?: "解析失败") }
            )
        }
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cloudbox_direct_link", text))
        }
    }
}
