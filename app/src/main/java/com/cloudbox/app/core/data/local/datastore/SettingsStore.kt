package com.cloudbox.app.core.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cloudbox.app.common.AppConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 应用设置（DataStore）。第一批先建骨架字段，后续批次（UA 自定义、
 * 后缀伪装开关、第三方解析服务 URL）直接复用此文件。
 */
@Singleton
class SettingsStore @Inject constructor(private val context: Context) {

    private val keyUserAgent = stringPreferencesKey("user_agent")
    private val keySuffixSpoof = booleanPreferencesKey("suffix_spoof_enabled")
    /** 需要伪装的上传后缀（逗号分隔），见 SpoofSuffixUtil */
    private val keySpoofSuffixList = stringPreferencesKey("spoof_suffix_list")
    private val keyThirdPartyResolver = stringPreferencesKey("third_party_resolver_url")
    private val keyDarkMode = stringPreferencesKey("dark_mode") // system / light / dark
    private val keyWarnMobileNetwork = booleanPreferencesKey("warn_mobile_network")

    // ---- V30（对齐原版 v1.3.4.9 自定义设置页 / 消息设置页） ----
    private val keyShowFileTypeLabel = booleanPreferencesKey("show_file_type_label")
    private val keyShowAccountButton = booleanPreferencesKey("show_account_button")
    /** 收藏夹自动检查间隔（天，0=关闭）。用 String 存 Int，见字段注释 */
    private val keyAutoCheckDays = stringPreferencesKey("auto_check_favorites_time")
    /** 上次自动检查时间（epoch millis）。用 String 存 Long，保持与其他数值项一致 */
    private val keyLastAutoCheckTime = stringPreferencesKey("last_auto_check_time")

    // ---- V31（对齐原版「自动加载页面剩余内容」） ----
    private val keyAutoLoad = booleanPreferencesKey("auto_load")

    // ---- V33：公告已读 id（自建公告系统，见 Announcement） ----
    private val keyLastReadAnnouncementId = stringPreferencesKey("last_read_announcement_id")

    // ---- V33（第三批）：图标包 + 补充设置项 ----
    /** 当前使用的图标包目录（空串 = 用内置 Material 图标）。对齐原版 icon_pack_path */
    private val keyIconPackPath = stringPreferencesKey("icon_pack_path")
    /** 列表里显示「简介 / 密码」标记。对齐原版 show_desc_tag（默认开） */
    private val keyShowDescTag = booleanPreferencesKey("show_desc_tag")
    /** 文件标题双行显示。对齐原版 two_line_title（默认关） */
    private val keyTwoLineTitle = booleanPreferencesKey("two_line_title")
    /** 剪贴板分享链识别。对齐原版 get_clipboard（默认开） */
    private val keyGetClipboard = booleanPreferencesKey("get_clipboard")
    /** 删除二次确认。对齐原版 delete_secondary_confirmation（默认开） */
    private val keyDeleteConfirm = booleanPreferencesKey("delete_secondary_confirmation")

    // ---- V34（第五批）：下载位置与通知（对齐原版 download_folder / send_message） ----
    /** 系统下载器保存位置：Downloads 下的子目录名（空串 = 直接用 Downloads 根） */
    private val keyDownloadFolder = stringPreferencesKey("download_folder")
    /** 通知栏提醒（下载/上传完成是否弹通知）。对齐原版 send_message（默认开） */
    private val keySendMessage = booleanPreferencesKey("send_message")

    /** 当前 UA：未自定义时返回默认桌面 UA（伪装关键） */
    val userAgent: Flow<String> = context.settingsDataStore.data.map {
        it[keyUserAgent] ?: AppConstants.DESKTOP_UA
    }

    /**
     * 上传前是否把「不支持格式」改名成 `.zip`。
     *
     * ⚠️ 默认 **关闭**（2026-09-23 修正，此前默认开启是错的）：
     * - 原版 `update_log.lua` 1.2.4.0 写得很清楚：「由于官方封堵，上传不支持文件
     *   修改后缀为 .zip，**且此功能默认关闭**」—— 原版就是默认关，
     *   这里当初"对齐原版"对反了。
     * - 更关键：「apk/exe 会被服务端拒」这个前提**从未被实测证明**。代码里唯一的
     *   实测记录是「**无扩展名**的文件被拒」，被过度推广成了「这些可执行格式都被
     *   拒」；实际 APK 是能直接上传成功的。
     *
     * 默认关闭的好处：上传保持原名，不再平白多出 `-v0.1.x.apk.zip` 这种又长、
     * 又要改回来才能用的名字。真碰上服务端回 `不能上传.格式的文件` 时，
     * 再到设置页打开这个开关即可（后缀列表也可编辑）。
     */
    val suffixSpoofEnabled: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keySuffixSpoof] ?: false
    }

    /**
     * 需要伪装的上传后缀（逗号分隔串）。空则用默认列表。
     * 见 [com.cloudbox.app.common.SpoofSuffixUtil]：蓝奏云的限制格式会变，
     * 硬编码列表每次都要改代码发版。
     */
    val spoofSuffixList: Flow<String> = context.settingsDataStore.data.map {
        it[keySpoofSuffixList] ?: com.cloudbox.app.common.SpoofSuffixUtil.DEFAULT_RAW
    }

    val thirdPartyResolverUrl: Flow<String> = context.settingsDataStore.data.map {
        it[keyThirdPartyResolver] ?: ""
    }

    val darkMode: Flow<String> = context.settingsDataStore.data.map {
        it[keyDarkMode] ?: "system"
    }

    /**
     * 移动网络下载前是否提醒。默认 **开启** —— 流量是用户的真金白银，
     * 默认不问一声就下载大文件是不礼貌的。
     */
    val warnMobileNetwork: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyWarnMobileNetwork] ?: true
    }

    // ==================== V30 新增（对齐原版 v1.3.4.9 自定义设置页） ====================

    /**
     * 是否在文件图标处显示文件后缀标签。
     *
     * 对齐原版 `show_file_type_label`（ty_core.lua:809 初始化为 **true**；
     * settings/customize_settings.lua 的「格式开关」可关）。
     * 默认**开启**——与账号按钮同理，原版是"默认给你看，不想要自己关"。
     */
    val showFileTypeLabel: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyShowFileTypeLabel] ?: true
    }

    /**
     * 是否显示账号入口按钮。
     *
     * 对齐原版 `show_account_button`（settings/customize_settings.lua
     * 「账号开关」/「账号计数」）。默认开启——不多账号切换的用户会关掉它。
     */
    val showAccountButton: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyShowAccountButton] ?: true
    }

    /**
     * 检查收藏文件夹更新的间隔（天）。
     *
     * 对齐原版 `auto_check_favorites` / `auto_check_favorites_time`
     * （settings/message_settings.lua：开关 + 1/3/7/14/30 天档位，
     * 文案「打开开关，"启动应用日期"与"上次检查日期"的间隔超过设定值后，
     * 将触发自动检查」）。
     *
     * 默认 **0 = 不自动检查**：后台定时抓别人网盘属于会消耗用户流量的行为，
     * 必须由用户显式开启。原版的默认值也是关。
     */
    val autoCheckFavoritesDays: Flow<Int> = context.settingsDataStore.data.map {
        // 用 string 存 Int，避免 preferencesDataStore 对 int key 的默认值歧义
        it[keyAutoCheckDays]?.toIntOrNull() ?: 0
    }

    /**
     * 上次自动检查收藏夹的时间（epoch millis）。0 = 从未检查过。
     *
     * 对齐原版 `last_auto_check_time`（home.lua:443 用它做间隔判断，
     * home_func.lua:6758 在检查完成后写入）。
     *
     * ⚠️ 原版有两个时间字段：`last_auto_check_time`（实际使用）和
     * `last_auto_check_favorites_time`（ty_core.lua 里初始化为 `false`，
     * 全程没有任何读写 —— 是个废弃字段）。这里只实现真正生效的那个，
     * 不把原版的死代码一起搬过来。
     */
    val lastAutoCheckTime: Flow<Long> = context.settingsDataStore.data.map {
        it[keyLastAutoCheckTime]?.toLongOrNull() ?: 0L
    }

    suspend fun setShowFileTypeLabel(enabled: Boolean) =
        edit { p -> p[keyShowFileTypeLabel] = enabled }

    suspend fun setShowAccountButton(enabled: Boolean) =
        edit { p -> p[keyShowAccountButton] = enabled }

    suspend fun setAutoCheckFavoritesDays(days: Int) =
        edit { p -> p[keyAutoCheckDays] = days.coerceIn(0, 30).toString() }

    suspend fun setLastAutoCheckTime(millis: Long) =
        edit { p -> p[keyLastAutoCheckTime] = millis.toString() }

    /**
     * 自动加载列表剩余内容（滚动到底自动续拉下一页）。
     *
     * 对齐原版 `auto_load`（ty_core.lua:754 初始化为 **false**；
     * settings/action_settings.lua 的开关文案是「自动加载页面剩余内容」）。
     *
     * ⚠️ 原版默认是 **关**。我们这里默认**开**，这是有意偏离，理由：
     * 原版关掉它时，列表底部仍然会挂一个"点击加载下一页"的触发区；
     * 我们这一版已经把底部按钮彻底去掉（用户明确要求不要那行字），
     * 若再默认关闭自动加载，用户就**没有办法**加载第二页了 —— 功能死锁。
     * 所以：默认开，且关掉后仍有"滚动到底自动加载"作为唯一路径。
     *
     * 保留这个开关是因为原版界面里有它，用户可能有"省流量、不要预读"的诉求。
     */
    val autoLoad: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyAutoLoad] ?: true
    }

    suspend fun setAutoLoad(enabled: Boolean) =
        edit { p -> p[keyAutoLoad] = enabled }

    /**
     * 最近一次已读公告的 id（空串 = 从未读过）。
     *
     * ⚠️ 刻意**不**纳入 [snapshotAll]/[applySnapshot] 的备份范围：已读状态是
     * "本机看到哪了"的本地视角，换机后重新亮一次红点比"恢复备份后永远不再提示"
     * 更符合直觉。
     */
    val lastReadAnnouncementId: Flow<String> = context.settingsDataStore.data.map {
        it[keyLastReadAnnouncementId] ?: ""
    }

    suspend fun setLastReadAnnouncementId(id: String) =
        edit { p -> p[keyLastReadAnnouncementId] = id }

    // ---- V33（第三批）：图标包 + 补充设置项 ----

    /** 当前图标包目录（空串 = 内置 Material 图标） */
    val iconPackPath: Flow<String> = context.settingsDataStore.data.map {
        it[keyIconPackPath] ?: ""
    }

    suspend fun setIconPackPath(path: String) = edit { p -> p[keyIconPackPath] = path }

    /** 列表显示简介/密码标记（原版 show_desc_tag，默认开） */
    val showDescTag: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyShowDescTag] ?: true
    }

    suspend fun setShowDescTag(enabled: Boolean) = edit { p -> p[keyShowDescTag] = enabled }

    /** 文件标题双行显示（原版 two_line_title，默认关） */
    val twoLineTitle: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyTwoLineTitle] ?: false
    }

    suspend fun setTwoLineTitle(enabled: Boolean) = edit { p -> p[keyTwoLineTitle] = enabled }

    /** 剪贴板分享链识别（原版 get_clipboard，默认开） */
    val getClipboard: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyGetClipboard] ?: true
    }

    suspend fun setGetClipboard(enabled: Boolean) = edit { p -> p[keyGetClipboard] = enabled }

    /** 删除二次确认（原版 delete_secondary_confirmation，默认开） */
    val deleteConfirm: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyDeleteConfirm] ?: true
    }

    suspend fun setDeleteConfirm(enabled: Boolean) = edit { p -> p[keyDeleteConfirm] = enabled }

    // ---- V34（第五批）：下载位置与通知（对齐原版 download_folder / send_message） ----

    /**
     * 系统下载器的保存位置：Downloads 目录下的**子目录名**（空串 = 直接用 Downloads 根）。
     *
     * 对齐原版 `download_folder`（ty_core.lua:947-948 初始化为 `""`；
     * 弹窗写入 ds_layout.lua:1132-1148；显示 ds.lua:49-53）。
     * 原版语义：`""` = 内部存储/Download，`/LanCloud` = 内部存储/Download/LanCloud。
     * 这里存**不带斜杠的子目录名**，由使用处拼进 DownloadManager 的目标路径。
     *
     * 为什么不做成"任意绝对路径"：Android 10+ 分区存储下 DownloadManager 只能写
     * 公共 Downloads 或自家目录，让用户填任意路径只会换来一堆"下载失败"。
     * 只给"Downloads 下的子目录"既能满足归类需求，又不会踩权限坑。
     */
    val downloadFolder: Flow<String> = context.settingsDataStore.data.map {
        it[keyDownloadFolder] ?: ""
    }

    /** 保存下载子目录名（写入前消毒，见 [sanitizeFolderName]） */
    suspend fun setDownloadFolder(name: String) =
        edit { p -> p[keyDownloadFolder] = sanitizeFolderName(name) }

    /**
     * 通知栏提醒：下载/上传完成是否弹系统通知。
     *
     * 对齐原版 `send_message`（ty_core.lua:899-900 初始化为 **true**，
     * 消息设置页的开关文案是「通知栏提醒」）。
     */
    val sendMessage: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keySendMessage] ?: true
    }

    suspend fun setSendMessage(enabled: Boolean) = edit { p -> p[keySendMessage] = enabled }

    /**
     * 下载子目录名消毒。
     *
     * 去掉路径分隔符与 `..`：这个值会被拼进 DownloadManager 的目标路径，不过滤的话
     * `../../` 之类能把文件写到 Downloads 之外（越权写）。顺带把空白串归一成 `""`
     * （等价于"不建子目录"）。
     */
    private fun sanitizeFolderName(raw: String): String =
        raw.trim().replace(Regex("[/\\\\]"), "").replace("..", "").trim()

    // 注：曾经有过 preferWebUpload（上传通道开关），2026-09-09 移除。
    // 上传一律走 App 原生直传——原版 App 就是这么做的，设置页的「上传通道自检」
    // 也实测证明这条路能真正上传成功。网页上传降级为设置页的一个手动入口，
    // 不再需要一个"默认通道"开关。

    suspend fun setUserAgent(ua: String) = edit { p -> p[keyUserAgent] = ua }

    suspend fun setSuffixSpoof(enabled: Boolean) = edit { p -> p[keySuffixSpoof] = enabled }

    suspend fun setSpoofSuffixList(raw: String) = edit { p ->
        p[keySpoofSuffixList] = com.cloudbox.app.common.SpoofSuffixUtil.normalize(raw)
    }

    suspend fun setThirdPartyResolver(url: String) = edit { p -> p[keyThirdPartyResolver] = url }

    suspend fun setDarkMode(mode: String) = edit { p -> p[keyDarkMode] = mode }

    suspend fun setWarnMobileNetwork(enabled: Boolean) = edit { p -> p[keyWarnMobileNetwork] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }

    // ==================== V32：备份 / 恢复 / 重置 ====================

    /**
     * 全部设置项的**字符串快照**（key 名 → 值）。
     *
     * 为什么统一转成字符串：Play 备份/恢复这类场景下，键值类型会随版本变化，
     * 用字符串承载可以避免"旧备份里是 int、新版本读成 String"这类崩溃。
     * 每个 key 的语义由 [applySnapshot] 按名字还原。
     *
     * ⚠️ 这里手工列举，而不是遍历 DataStore 的 preferences.asMap()。
     * 遍历的写法看着更"自动"，但会把**将来新增的 key** 悄悄带进备份，
     * 而那些 key 在旧版本的 [applySnapshot] 里没有还原分支 —— 恢复后
     * 数据静默丢失，问题极难查。宁可新增设置项时多写一行。
     */
    suspend fun snapshotAll(): Map<String, String> {
        val p = context.settingsDataStore.data.first()
        return buildMap {
            p[keyUserAgent]?.let { put(KEY_USER_AGENT, it) }
            p[keySuffixSpoof]?.let { put(KEY_SUFFIX_SPOOF, it.toString()) }
            p[keySpoofSuffixList]?.let { put(KEY_SPOOF_SUFFIX_LIST, it) }
            p[keyThirdPartyResolver]?.let { put(KEY_THIRD_PARTY_RESOLVER, it) }
            p[keyDarkMode]?.let { put(KEY_DARK_MODE, it) }
            p[keyWarnMobileNetwork]?.let { put(KEY_WARN_MOBILE_NETWORK, it.toString()) }
            p[keyShowFileTypeLabel]?.let { put(KEY_SHOW_FILE_TYPE_LABEL, it.toString()) }
            p[keyShowAccountButton]?.let { put(KEY_SHOW_ACCOUNT_BUTTON, it.toString()) }
            p[keyAutoCheckDays]?.let { put(KEY_AUTO_CHECK_DAYS, it) }
            p[keyLastAutoCheckTime]?.let { put(KEY_LAST_AUTO_CHECK_TIME, it) }
            p[keyAutoLoad]?.let { put(KEY_AUTO_LOAD, it.toString()) }
            p[keyDownloadFolder]?.let { put(KEY_DOWNLOAD_FOLDER, it) }
            p[keySendMessage]?.let { put(KEY_SEND_MESSAGE, it.toString()) }
        }
    }

    /**
     * 按 key 覆盖写回（**不清空**备份里没有的 key）。
     *
     * 不全量替换的原因见 [snapshotAll] 注释：备份来自旧版本时，
     * 它没有后来新增的 key，全量替换会把那些设置重置成默认值 ——
     * 用户会觉得"恢复一次备份，把我另外几项设置搞没了"。
     */
    suspend fun applySnapshot(map: Map<String, String>) = edit { p ->
        map[KEY_USER_AGENT]?.let { p[keyUserAgent] = it }
        map[KEY_SUFFIX_SPOOF]?.toLooseBool()?.let { p[keySuffixSpoof] = it }
        map[KEY_SPOOF_SUFFIX_LIST]?.let { p[keySpoofSuffixList] = it }
        map[KEY_THIRD_PARTY_RESOLVER]?.let { p[keyThirdPartyResolver] = it }
        map[KEY_DARK_MODE]?.let { p[keyDarkMode] = it }
        map[KEY_WARN_MOBILE_NETWORK]?.toLooseBool()?.let { p[keyWarnMobileNetwork] = it }
        map[KEY_SHOW_FILE_TYPE_LABEL]?.toLooseBool()?.let { p[keyShowFileTypeLabel] = it }
        map[KEY_SHOW_ACCOUNT_BUTTON]?.toLooseBool()?.let { p[keyShowAccountButton] = it }
        map[KEY_AUTO_CHECK_DAYS]?.let { p[keyAutoCheckDays] = it }
        map[KEY_LAST_AUTO_CHECK_TIME]?.let { p[keyLastAutoCheckTime] = it }
        map[KEY_AUTO_LOAD]?.toLooseBool()?.let { p[keyAutoLoad] = it }
        map[KEY_DOWNLOAD_FOLDER]?.let { p[keyDownloadFolder] = sanitizeFolderName(it) }
        map[KEY_SEND_MESSAGE]?.toLooseBool()?.let { p[keySendMessage] = it }
    }

    /**
     * 宽松的布尔解析。
     *
     * 为什么不用 `toBooleanStrictOrNull()`：那个函数**只认**小写
     * `"true"` / `"false"`，其余一律返回 null。而我们这里的输入来自
     * 用户电脑上的备份文件 —— 用户完全可能手工改它，或者用别的工具
     * 生成（写 `True`、`1`、`yes`）。此时 `?.let` 会静默跳过，
     * 结果是"恢复了备份，但某一项设置没回来，且没有任何提示"——
     * 正是 [snapshotAll] 注释里批评过的那种静默失败。
     *
     * 这里对常见写法都容忍；实在认不出来才返回 null（调用方跳过该项，
     * 保留当前值 —— 比强行当成 false 更安全，至少不会平白改变用户设置）。
     */
    private fun String.toLooseBool(): Boolean? = when (trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }

    /**
     * 重置全部设置为默认值（「重置应用」用）。
     *
     * 直接 `clear()` 整个 Preferences：所有读取处都有 `?: 默认值` 兜底，
     * 清空即等价于"回出厂设置"，不需要把每个 key 逐个写成默认值 ——
     * 那样反而容易漏掉某个 key，导致"重置了但某一项还是旧的"。
     * **账号相关数据不在这个 DataStore 里**（走 EncryptedSharedPreferences），
     * 所以清空不会影响登录态。
     */
    suspend fun resetAll() {
        context.settingsDataStore.edit { it.clear() }
    }

    private companion object {
        // 快照用的 key 名。与上面 DataStore key 的字面量保持一致 ——
        // 这是备份文件的对外契约，改动会让旧备份无法恢复，务必谨慎。
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_SUFFIX_SPOOF = "suffix_spoof_enabled"
        const val KEY_SPOOF_SUFFIX_LIST = "spoof_suffix_list"
        const val KEY_THIRD_PARTY_RESOLVER = "third_party_resolver_url"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_WARN_MOBILE_NETWORK = "warn_mobile_network"
        const val KEY_SHOW_FILE_TYPE_LABEL = "show_file_type_label"
        const val KEY_SHOW_ACCOUNT_BUTTON = "show_account_button"
        const val KEY_AUTO_CHECK_DAYS = "auto_check_favorites_time"
        const val KEY_LAST_AUTO_CHECK_TIME = "last_auto_check_time"
        const val KEY_AUTO_LOAD = "auto_load"
        // V34（第五批）：下载位置与通知
        const val KEY_DOWNLOAD_FOLDER = "download_folder"
        const val KEY_SEND_MESSAGE = "send_message"
    }
}
