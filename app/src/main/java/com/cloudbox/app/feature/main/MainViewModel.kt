package com.cloudbox.app.feature.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.common.ClipboardLinkWatcher
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.core.domain.repository.DirectLinkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 主界面 ViewModel：聚合剪贴板监听器 + 认证仓库 + 直链解析（弹窗"获取直链"用） */
@HiltViewModel
class MainViewModel @Inject constructor(
    val clipboardWatcher: ClipboardLinkWatcher,
    val authRepository: AuthRepository,
    /** V30：主界面读取界面显示设置（show_account_button / show_file_type_label） */
    val settingsStore: SettingsStore,
    /** V33：主界面"关于"入口的红点来源 */
    val updateStatusStore: com.cloudbox.app.core.data.update.UpdateStatusStore,
    /** V33：主界面公告入口的红点来源 */
    val announcementStatusStore: com.cloudbox.app.core.data.announcement.AnnouncementStatusStore,
    private val directLinkRepository: DirectLinkRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    // ==================== V51：多账号（抽屉「切换账户」入口数据源） ====================

    private val _accounts = MutableStateFlow<List<com.cloudbox.app.core.domain.model.AccountInfo>>(emptyList())

    /** V51：全部已保存账号（抽屉切换账户弹窗用） */
    val accounts = _accounts.asStateFlow()

    private val _switchMsg = MutableStateFlow<String?>(null)

    /** V51：切换/删除账号的结果提示（一次性消息） */
    val switchMsg = _switchMsg.asStateFlow()

    /** V51：加载账号列表（进入抽屉弹窗时调用） */
    fun loadAccounts() {
        viewModelScope.launch {
            _accounts.value = authRepository.allAccounts()
        }
    }

    /** V51：切换账号（对齐原版 home_func.lua「选择已有账号登录」→ cookie 登录） */
    fun switchAccount(uid: String) {
        viewModelScope.launch {
            val ok = authRepository.switchAccount(uid)
            _switchMsg.value = if (ok) "已切换到 $uid" else "切换失败（Cookie 可能已失效）"
            if (ok) _accounts.value = authRepository.allAccounts()
        }
    }

    /** V51：删除账号槽位（含 Cookie） */
    fun removeAccount(uid: String) {
        viewModelScope.launch {
            authRepository.logout(uid)
            _accounts.value = authRepository.allAccounts()
        }
    }

    fun consumeSwitchMsg() { _switchMsg.value = null }

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
