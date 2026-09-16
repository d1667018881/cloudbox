package com.cloudbox.app.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.common.UploadTrace
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
    /** 需要伪装的上传后缀（逗号分隔） */
    val spoofSuffixList: String = com.cloudbox.app.common.SpoofSuffixUtil.DEFAULT_RAW,
    val thirdPartyResolver: String = "",
    val darkMode: String = "system",
    /** 应用内语言：system / zh / en */
    val appLanguage: String = "system",
    /** 移动网络下载前提醒（默认开） */
    val warnMobileNetwork: Boolean = true,
    /**
     * V30：列表里显示文件类型标签（图片/视频/压缩包…）。
     *
     * 对齐原版 `show_file_type_label`。默认**关**：原版默认也是关，
     * 且标签对已知扩展名的文件是冗余信息，只在用户明确想看时才占位。
     */
    val showFileTypeLabel: Boolean = false,
    /**
     * V30：是否显示账号入口按钮。
     *
     * 对齐原版 `show_account_button`。默认**开**：这是 App 的主要入口之一，
     * 关掉它用户就没有直达账号中心的路径了，属于"用户主动关闭"型开关。
     */
    val showAccountButton: Boolean = true,
    /**
     * V30：收藏夹自动检查更新的间隔（天）。0 = 关闭。
     *
     * 对齐原版 `auto_check_favorites_time`（原版存的是字符串，
     * 这里改为 Int 更好比较；存储层仍以字符串落盘以保持兼容）。
     */
    val autoCheckFavoritesDays: Int = 0,
    /**
     * V31：自动加载列表剩余内容（滚动到底自动续拉下一页）。
     *
     * 对齐原版 `auto_load`「自动加载页面剩余内容」。**默认开**
     * （原版默认关，这里有意不同 —— 我们已去掉"加载更多"按钮，
     * 默认关会让用户无法加载第二页，详见 SettingsStore.autoLoad 注释）。
     */
    val autoLoad: Boolean = true,
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
    private val uploadRepository: UploadRepository,
    private val uploadTrace: UploadTrace
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * 上传链路时间线（全局单例，跨页面/跨 Worker）。
     *
     * 为什么要有这个持久入口：上传失败时网盘页只弹一个 Snackbar，
     * 一旦被划掉或错过就再也看不到原因（"点看详情看不到"）。
     * 这里把同一份时间线挂到设置页，任何时候都能回来翻。
     */
    val uploadTimeline: StateFlow<List<String>> = uploadTrace.lines

    /** 清空时间线（排查前先清一次，时间线更干净） */
    fun clearUploadTimeline() = uploadTrace.clear()

    init {
        viewModelScope.launch {
            val ua = settingsStore.userAgent.first()
            val spoof = settingsStore.suffixSpoofEnabled.first()
            val spoofList = settingsStore.spoofSuffixList.first()
            val resolver = settingsStore.thirdPartyResolverUrl.first()
            val dark = settingsStore.darkMode.first()
            val lang = settingsStore.appLanguage.first()
            val warnMobile = settingsStore.warnMobileNetwork.first()
            val showTypeLabel = settingsStore.showFileTypeLabel.first()
            val showAccountBtn = settingsStore.showAccountButton.first()
            val autoCheckDays = settingsStore.autoCheckFavoritesDays.first()
            val autoLoadPref = settingsStore.autoLoad.first()
            _uiState.update {
                it.copy(
                    userAgent = ua, suffixSpoof = spoof,
                    spoofSuffixList = spoofList,
                    thirdPartyResolver = resolver, darkMode = dark,
                    appLanguage = lang, warnMobileNetwork = warnMobile,
                    showFileTypeLabel = showTypeLabel,
                    showAccountButton = showAccountBtn,
                    autoCheckFavoritesDays = autoCheckDays,
                    autoLoad = autoLoadPref
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

    /** 保存自定义伪装后缀（写入前会规范化：去点号、去重、统一小写） */
    fun saveSpoofSuffixList(raw: String) {
        viewModelScope.launch {
            settingsStore.setSpoofSuffixList(raw)
            val normalized = com.cloudbox.app.common.SpoofSuffixUtil.normalize(raw)
            _uiState.update {
                it.copy(spoofSuffixList = normalized, message = "后缀列表已保存")
            }
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

    /**
     * 保存应用内语言。
     *
     * ⚠️ 提示用户"需要重启"而不是自动重建 Activity：
     * 自动 `recreate()` 在语言变更时会走一次完整的销毁-重建，
     * 而此时可能正好有后台任务（上传 Worker 的进度回调绑在 ViewModel 上），
     * 重建会打断回调。让用户自己决定何时重启更稳妥，
     * 且设置已经写盘，重启后必然生效。
     */
    fun saveAppLanguage(lang: String) {
        viewModelScope.launch {
            settingsStore.setAppLanguage(lang)
            _uiState.update {
                it.copy(appLanguage = lang, message = "语言已保存，重启 App 后生效")
            }
        }
    }

    /** 移动网络下载提醒开关 */
    fun saveWarnMobileNetwork(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setWarnMobileNetwork(enabled)
            _uiState.update { it.copy(warnMobileNetwork = enabled) }
        }
    }

    // ==================== V30：界面显示 / 收藏夹自动检查 ====================

    /**
     * 是否在列表里显示文件类型标签。
     *
     * 这里显式弹一条提示而不是静默保存：这个开关影响的是**别的页面**的观感，
     * 用户在设置页拨动开关后看不见任何变化，容易以为没生效而反复点。
     */
    fun saveShowFileTypeLabel(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setShowFileTypeLabel(enabled)
            _uiState.update {
                it.copy(showFileTypeLabel = enabled, message = if (enabled) "已显示文件类型标签" else "已隐藏文件类型标签")
            }
        }
    }

    /** 是否显示账号入口按钮 */
    fun saveShowAccountButton(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setShowAccountButton(enabled)
            _uiState.update { it.copy(showAccountButton = enabled) }
        }
    }

    /**
     * 自动加载列表剩余内容（对齐原版 auto_load）。
     *
     * 关掉后列表滚动到底不再自动续拉，需要下拉刷新才能看到更新 ——
     * 适合"流量敏感、不想让 App 预读后面几页"的用户。
     */
    fun saveAutoLoad(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoLoad(enabled)
            _uiState.update {
                it.copy(
                    autoLoad = enabled,
                    message = if (enabled) "已开启自动加载剩余内容" else "已关闭自动加载剩余内容"
                )
            }
        }
    }

    /**
     * 收藏夹自动检查间隔（天），0 = 关闭。
     *
     * 只写"间隔"这一个值，不再单独存一个开关：开关状态由 `days > 0` 推导。
     * 两个字段（enabled + days）来存同一件事，迟早会出现
     * "开关是开的但天数是 0"这种自相矛盾的状态，不如从一开始就只留一个真值来源。
     */
    fun saveAutoCheckFavoritesDays(days: Int) {
        viewModelScope.launch {
            val safe = days.coerceAtLeast(0)
            settingsStore.setAutoCheckFavoritesDays(safe)
            // 关闭时立刻取消排队中的检查（对齐原版把开关关掉的即时效果）。
            // 开启时**不**立即发起检查 —— 原版也是"等下次满足间隔条件"才跑，
            // 否则用户一开开关就被抓一次全部收藏夹，与他预期不符。
            if (safe <= 0) {
                com.cloudbox.app.feature.favorites.FavoriteUpdateCheckScheduler
                    .cancel(context.applicationContext)
            }
            _uiState.update {
                it.copy(
                    autoCheckFavoritesDays = safe,
                    message = if (safe > 0) "已开启收藏夹自动检查（每 $safe 天）" else "已关闭收藏夹自动检查"
                )
            }
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
