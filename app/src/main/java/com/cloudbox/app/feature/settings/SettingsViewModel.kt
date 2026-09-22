package com.cloudbox.app.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.data.local.iconpack.IconPackStore
import com.cloudbox.app.core.data.repository.DataBackupRepository
import com.cloudbox.app.core.domain.model.AccountInfo
import com.cloudbox.app.core.domain.model.IconPack
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.core.domain.repository.ProfileRepository
import com.cloudbox.app.core.domain.repository.ProfileResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val message: String? = null,
    // ==================== V32：数据管理 ====================
    /** 缓存占用字节数（设置页展示用；进入页面时算一次，清完再算一次） */
    val cacheBytes: Long = 0L,
    /** 备份 / 恢复 / 重置等操作进行中，用于禁用按钮 + 转圈 */
    val dataBusy: Boolean = false,
    /** 恢复前的预览：用户选完文件后先弹它确认，确认了才真正写库 */
    val restorePreview: RestorePreviewUi? = null,
    /** 恢复确认弹窗要用的原始备份文本（预览时读出来的，避免再读一次文件） */
    val pendingRestoreJson: String? = null,
    /**
     * UA / 第三方解析 URL 的**输入框缓存**。
     *
     * 为什么放在 ViewModel 而不是 Composable 的 `remember` 里：
     * `remember` 只在首次组合时取初值，dialog 关闭（从组合树移除）后
     * 那份值不会丢，再次打开时依旧是旧的。于是"恢复备份改了设置 → 打开
     * 输入框看到的还是旧文本"这种自相矛盾就会出现（详见 [reloadSettingsFromStore]）。
     * 放进 state 后，输入框可以靠 `LaunchedEffect` 与 state 保持同步。
     */
    val uaInput: String = "",
    /** 同 [uaInput]，见其注释 */
    val resolverInput: String = "",
    // ==================== V33（第三批） ====================
    /** 已导入的图标包列表 */
    val iconPacks: List<IconPack> = emptyList(),
    /** 当前图标包目录（空串 = 内置 Material 图标） */
    val iconPackPath: String = "",
    /** 列表显示「简介 / 密码」标记 */
    val showDescTag: Boolean = true,
    /** 文件标题双行显示 */
    val twoLineTitle: Boolean = false,
    /** 剪贴板分享链识别 */
    val getClipboard: Boolean = true,
    /** 删除二次确认 */
    val deleteConfirm: Boolean = true,
    // ==================== V33：备份增强 ====================
    /** 生成好的备份码（非空时 UI 弹窗展示 + 可复制） */
    val backupCode: String? = null,
    /** 恢复预览用到的密码（加密备份码需要，confirmRestore 复用） */
    val restorePassword: String? = null,
    /** 增量恢复：保留现有收藏，只追加备份里的条目 */
    val mergeRestore: Boolean = false
)

/** 恢复预览（UI 层副本，不直接暴露 Repository 的 data class，避免 UI 依赖数据层类型） */
data class RestorePreviewUi(
    val favoriteCount: Int,
    val settingCount: Int,
    val backupTime: Long,
    val backupAppVersion: String,
    /** 空备份：UI 需用红字警告"恢复它等于清空当前数据" */
    val isEmpty: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val dataBackupRepository: DataBackupRepository,
    private val iconPackStore: IconPackStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
            val iconPack = settingsStore.iconPackPath.first()
            val showDesc = settingsStore.showDescTag.first()
            val twoLine = settingsStore.twoLineTitle.first()
            val clipboardPref = settingsStore.getClipboard.first()
            val deleteConfirmPref = settingsStore.deleteConfirm.first()
            _uiState.update {
                it.copy(
                    userAgent = ua, suffixSpoof = spoof,
                    spoofSuffixList = spoofList,
                    thirdPartyResolver = resolver, darkMode = dark,
                    appLanguage = lang, warnMobileNetwork = warnMobile,
                    showFileTypeLabel = showTypeLabel,
                    showAccountButton = showAccountBtn,
                    autoCheckFavoritesDays = autoCheckDays,
                    autoLoad = autoLoadPref,
                    uaInput = ua,
                    resolverInput = resolver,
                    iconPackPath = iconPack,
                    showDescTag = showDesc,
                    twoLineTitle = twoLine,
                    getClipboard = clipboardPref,
                    deleteConfirm = deleteConfirmPref,
                    iconPacks = iconPackStore.listPacks()
                )
            }
        }
        viewModelScope.launch {
            authRepository.currentAccount.collect { acc ->
                _uiState.update { it.copy(currentUid = acc?.uid) }
            }
        }
        loadAccounts()
        refreshCacheSize()
    }

    fun loadAccounts() {
        viewModelScope.launch {
            val accounts = authRepository.allAccounts()
            _uiState.update { it.copy(accounts = accounts) }
        }
    }

    fun saveUserAgent(ua: String) {
        viewModelScope.launch {
            val safe = ua.trim().ifEmpty { AppConstants.DESKTOP_UA }
            settingsStore.setUserAgent(safe)
            // 同步输入框缓存（理由见 [reloadSettingsFromStore]）：
            // 用户清空输入框时 state 会回落默认 UA，输入框也必须跟着回落，
            // 否则下次打开看到的是一片空白，而实际生效的是默认 UA。
            _uiState.update { it.copy(userAgent = safe, uaInput = safe, message = "UA 已保存（立即生效）") }
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
            val safe = url.trim()
            settingsStore.setThirdPartyResolver(safe)
            // 输入框缓存同步成规范化后的值：手动输入时是"点保存 → dialog 立刻关闭"，
            // 看不出差别；但「恢复备份」会直接改这个设置，下一次打开 dialog 时
            // 输入框拿到的还是关闭时留下的旧文本，用户会以为恢复没生效。
            // 详见 [reloadSettingsFromStore]。
            _uiState.update { it.copy(thirdPartyResolver = safe, resolverInput = safe) }
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

    // ==================== V33（第三批）：补充设置项 ====================

    /** 列表显示「简介 / 密码」标记（原版 show_desc_tag） */
    fun saveShowDescTag(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setShowDescTag(enabled)
            _uiState.update { it.copy(showDescTag = enabled) }
        }
    }

    /** 文件标题双行显示（原版 two_line_title） */
    fun saveTwoLineTitle(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setTwoLineTitle(enabled)
            _uiState.update { it.copy(twoLineTitle = enabled) }
        }
    }

    /** 剪贴板分享链识别（原版 get_clipboard） */
    fun saveGetClipboard(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGetClipboard(enabled)
            _uiState.update { it.copy(getClipboard = enabled) }
        }
    }

    /** 删除二次确认（原版 delete_secondary_confirmation） */
    fun saveDeleteConfirm(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setDeleteConfirm(enabled)
            _uiState.update { it.copy(deleteConfirm = enabled) }
        }
    }

    // ==================== V33：图标包 ====================

    /** 导入 zip 图标包（解压到 icon_pack/，校验 info.json） */
    fun importIconPack(uri: android.net.Uri) {
        viewModelScope.launch {
            iconPackStore.importZip(uri)
                .onSuccess { pack ->
                    _uiState.update {
                        it.copy(
                            iconPacks = iconPackStore.listPacks(),
                            message = "已导入图标包「${pack.name}」"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(message = "导入失败：${e.message}") }
                }
        }
    }

    /** 切换图标包（path 为空串 = 恢复内置图标） */
    fun switchIconPack(path: String) {
        viewModelScope.launch {
            settingsStore.setIconPackPath(path)
            _uiState.update {
                it.copy(
                    iconPackPath = path,
                    message = if (path.isEmpty()) "已恢复内置图标" else "已切换图标包"
                )
            }
        }
    }

    /** 删除图标包；若正被使用则回退内置图标 */
    fun deleteIconPack(path: String) {
        viewModelScope.launch {
            iconPackStore.deletePack(path)
                .onSuccess {
                    val fallback = if (_uiState.value.iconPackPath == path) {
                        settingsStore.setIconPackPath("")
                        ""
                    } else {
                        _uiState.value.iconPackPath
                    }
                    _uiState.update {
                        it.copy(
                            iconPacks = iconPackStore.listPacks(),
                            iconPackPath = fallback,
                            message = "已删除图标包"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(message = "删除失败：${e.message}") }
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

    // ==================== V32：数据管理 ====================

    /**
     * 重算缓存占用。遍历目录是 IO，放 IO 线程；同时包一层 busy 状态，
     * 避免用户连点"清除缓存"时几个协程同时删目录。
     */
    fun refreshCacheSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { dataBackupRepository.cacheSizeBytes() }
            _uiState.update { it.copy(cacheBytes = bytes) }
        }
    }

    /**
     * 备份数据到文件。
     *
     * 成功后**把文件路径显示在 snackbar 里**而不是只说"备份成功"：
     * 用户下一步必然要去找这个文件，不告诉他放哪儿等于没备份。
     */
    fun backupData() {
        if (_uiState.value.dataBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(dataBusy = true) }
            runCatching {
                val (file, summary) = dataBackupRepository.writeBackupFile(appVersionName())
                val where = dataBackupRepository.describeLocation(file)
                "已备份 ${summary.favoriteCount} 个收藏、${summary.settingCount} 项设置\n位置：$where"
            }.onSuccess { text ->
                _uiState.update { it.copy(dataBusy = false, message = text) }
            }.onFailure { e ->
                _uiState.update { it.copy(dataBusy = false, message = "备份失败：${e.readableMessage()}") }
            }
        }
    }

    /**
     * 生成「备份码」（可复制 / 分享的文本形式）。
     * password 为空 → 明文 JSON 文本；非空 → 加密成 `CBOX1:` 串（见 BackupCrypto）。
     */
    fun buildBackupCode(password: String?) {
        if (_uiState.value.dataBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(dataBusy = true, backupCode = null) }
            runCatching {
                val (json, _) = dataBackupRepository.buildBackupJson(appVersionName())
                dataBackupRepository.buildBackupToken(json, password)
            }.onSuccess { code ->
                _uiState.update { it.copy(dataBusy = false, backupCode = code) }
            }.onFailure { e ->
                _uiState.update { it.copy(dataBusy = false, message = "生成备份码失败：${e.readableMessage()}") }
            }
        }
    }

    fun dismissBackupCode() = _uiState.update { it.copy(backupCode = null) }

    /** 从粘贴的备份码恢复：只解析 + 预览，不写库 */
    fun prepareRestoreFromCode(token: String, password: String?) {
        if (_uiState.value.dataBusy) return
        val text = token.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(dataBusy = true) }
            dataBackupRepository.inspect(text, password).onSuccess { p ->
                _uiState.update {
                    it.copy(
                        dataBusy = false,
                        pendingRestoreJson = text,
                        restorePassword = password,
                        restorePreview = RestorePreviewUi(
                            favoriteCount = p.favoriteCount,
                            settingCount = p.settingCount,
                            backupTime = p.backupTime,
                            backupAppVersion = p.backupAppVersion,
                            isEmpty = p.isEmpty
                        )
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(dataBusy = false, message = "备份码无效：${e.readableMessage()}") }
            }
        }
    }

    /** 预览弹窗里的「增量添加」复选框 */
    fun setMergeRestore(merge: Boolean) = _uiState.update { it.copy(mergeRestore = merge) }

    /**
     * 用户从系统文件选择器选好备份文件后调用：只读取 + 解析，**不写库**。
     *
     * 恢复是破坏性的（会清空现有收藏），必须让用户先看到"这份备份是几号的、
     * 里面有多少条"再决定。所以这里只把预览放进 state，真正的写入在
     * [confirmRestore] 里由用户二次确认后触发。
     */
    fun prepareRestore(uri: android.net.Uri) {
        if (_uiState.value.dataBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(dataBusy = true) }
            val text = dataBackupRepository.readBackupText(uri).getOrElse { e ->
                _uiState.update { it.copy(dataBusy = false, message = "读取文件失败：${e.readableMessage()}") }
                return@launch
            }
            dataBackupRepository.inspect(text).onSuccess { p ->
                _uiState.update {
                    it.copy(
                        dataBusy = false,
                        pendingRestoreJson = text,
                        restorePassword = null,
                        restorePreview = RestorePreviewUi(
                            favoriteCount = p.favoriteCount,
                            settingCount = p.settingCount,
                            backupTime = p.backupTime,
                            backupAppVersion = p.backupAppVersion,
                            isEmpty = p.isEmpty
                        )
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(dataBusy = false, message = "这个文件不是有效备份：${e.readableMessage()}") }
            }
        }
    }

    /** 用户在预览弹窗里点了「确认恢复」 */
    fun confirmRestore() {
        val token = _uiState.value.pendingRestoreJson ?: return
        val preview = _uiState.value.restorePreview ?: return
        if (_uiState.value.dataBusy) return
        val password = _uiState.value.restorePassword
        val merge = _uiState.value.mergeRestore
        viewModelScope.launch {
            _uiState.update {
                it.copy(dataBusy = true, restorePreview = null, pendingRestoreJson = null, restorePassword = null)
            }
            // 空备份：把用户的"确认"当作显式授权（预览弹窗已经用红字警告过）。
            // 非空备份走默认的 false，多一层保险。
            dataBackupRepository.restore(
                token = token,
                allowEmptyFavorites = preview.isEmpty,
                password = password,
                mergeFavorites = merge
            )
                .onSuccess { s ->
                    // 恢复期间设置项被替换，界面上的开关值必须重新读一遍，
                    // 否则用户会看到"设置页显示的还是旧值，但实际已经是备份里的值"。
                    reloadSettingsFromStore()
                    val when_ = if (s.backupTime > 0) formatTime(s.backupTime) else "未知时间"
                    _uiState.update {
                        it.copy(dataBusy = false, message = "已恢复 ${s.favoriteCount} 个收藏（备份来自 $when_）")
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(dataBusy = false, message = "恢复失败：${e.readableMessage()}") }
                }
        }
    }

    /** 关闭预览弹窗（用户取消恢复） */
    fun dismissRestorePreview() {
        _uiState.update { it.copy(restorePreview = null, pendingRestoreJson = null) }
    }

    /**
     * 清缓存。
     *
     * ⚠️ 必须包 runCatching：仓储层在**有上传在途**时会抛异常拒绝执行
     * （待上传文件的唯一副本就在 cacheDir 下，删了就没了 —— 详见
     * DataBackupRepository.clearCache 的注释）。旧实现直接裸调，
     * 异常会逃逸出 launch → 未捕获 → 崩溃；或者 `dataBusy` 永远停在 true，
     * 之后所有数据管理按钮全部点不动。
     */
    fun clearCache() {
        if (_uiState.value.dataBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(dataBusy = true) }
            runCatching { dataBackupRepository.clearCache() }
                .onSuccess { freed ->
                    val bytes = withContext(Dispatchers.IO) { dataBackupRepository.cacheSizeBytes() }
                    _uiState.update {
                        it.copy(
                            dataBusy = false,
                            cacheBytes = bytes,
                            message = if (freed > 0) "已清除 ${formatBytes(freed)} 缓存" else "缓存已经是空的"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(dataBusy = false, message = e.readableMessage())
                    }
                }
        }
    }

    /**
     * 重置应用数据。
     *
     * 调用前 UI 已经弹过二次确认（见 SettingsScreen 的 resetConfirm 弹窗），
     * 这里不再确认。重置后必须重新读一遍设置项 + 账号列表：
     * 账号虽然不被重置，但当前账号可能因设置清空而变化（例如"显示账号按钮"开关）。
     */
    fun resetAppData() {
        if (_uiState.value.dataBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(dataBusy = true) }
            dataBackupRepository.resetAppData()
                .onSuccess {
                    reloadSettingsFromStore()
                    loadAccounts()
                    val bytes = withContext(Dispatchers.IO) { dataBackupRepository.cacheSizeBytes() }
                    _uiState.update {
                        it.copy(dataBusy = false, cacheBytes = bytes, message = "已重置为初始状态（登录状态已保留）")
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(dataBusy = false, message = "重置失败：${e.readableMessage()}") }
                }
        }
    }

    /** 重新从 DataStore 读全部设置项，覆盖到 UI state（恢复 / 重置后调用） */
    private suspend fun reloadSettingsFromStore() {
        val s = settingsStore
        // ⚠️ 设置页的 UA / 第三方解析 URL 是**弹窗里的输入框**，其初值必须在
        //    这里一并刷新，否则恢复/重置后会自相矛盾：
        //
        //    输入框原本写成 `remember { mutableStateOf(state.userAgent) }` ——
        //    remember 只在首次组合时取初值，且弹窗关闭（从组合树移除）后
        //    那份值不会丢。结果是"列表行显示的是新 UA，点开输入框看到的还是
        //    旧的"，用户顺手点一下「保存」就把刚恢复的值覆盖回去了。
        //
        //    现在输入框跟随 [SettingsUiState.uaInput] / [SettingsUiState.resolverInput]，
        //    所以这两个字段必须在这里跟着一起更新。
        _uiState.update {
            it.copy(
                userAgent = s.userAgent.first(),
                suffixSpoof = s.suffixSpoofEnabled.first(),
                spoofSuffixList = s.spoofSuffixList.first(),
                thirdPartyResolver = s.thirdPartyResolverUrl.first(),
                darkMode = s.darkMode.first(),
                appLanguage = s.appLanguage.first(),
                warnMobileNetwork = s.warnMobileNetwork.first(),
                showFileTypeLabel = s.showFileTypeLabel.first(),
                showAccountButton = s.showAccountButton.first(),
                autoCheckFavoritesDays = s.autoCheckFavoritesDays.first(),
                autoLoad = s.autoLoad.first(),
                // 输入框缓存跟着刷新，注释见本函数开头
                uaInput = s.userAgent.first(),
                resolverInput = s.thirdPartyResolverUrl.first()
            )
        }
    }

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
    }.getOrDefault("0.1.0")

    /** 异常转人话：JSON 解析类的异常直接抛 message 会是一串英文堆栈，太难懂 */
    private fun Throwable.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private fun formatTime(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(millis))

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
