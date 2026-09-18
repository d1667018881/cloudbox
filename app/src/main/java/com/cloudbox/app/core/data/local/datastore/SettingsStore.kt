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
    private val keyLanguage = stringPreferencesKey("app_language") // system / zh / en
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

    /** 当前 UA：未自定义时返回默认桌面 UA（伪装关键） */
    val userAgent: Flow<String> = context.settingsDataStore.data.map {
        it[keyUserAgent] ?: AppConstants.DESKTOP_UA
    }

    val suffixSpoofEnabled: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keySuffixSpoof] ?: true // 默认开启：不支持格式伪装为 .zip 上传
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
     * 应用内语言：system / zh / en。
     *
     * 原版 ty_core.lua 里的 `语言()` 函数反编译出来是恒等函数
     * （`fn32 = function(a1) return a1 end`），也就是说那个版本的多语言
     * 框架是空壳。所以这里不照搬，直接用 Android 标准的 per-app locale
     * —— 系统级支持、切完立刻生效、不需要自建词表。
     */
    val appLanguage: Flow<String> = context.settingsDataStore.data.map {
        it[keyLanguage] ?: "system"
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

    suspend fun setAppLanguage(lang: String) = edit { p -> p[keyLanguage] = lang }

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
            p[keyLanguage]?.let { put(KEY_LANGUAGE, it) }
            p[keyWarnMobileNetwork]?.let { put(KEY_WARN_MOBILE_NETWORK, it.toString()) }
            p[keyShowFileTypeLabel]?.let { put(KEY_SHOW_FILE_TYPE_LABEL, it.toString()) }
            p[keyShowAccountButton]?.let { put(KEY_SHOW_ACCOUNT_BUTTON, it.toString()) }
            p[keyAutoCheckDays]?.let { put(KEY_AUTO_CHECK_DAYS, it) }
            p[keyLastAutoCheckTime]?.let { put(KEY_LAST_AUTO_CHECK_TIME, it) }
            p[keyAutoLoad]?.let { put(KEY_AUTO_LOAD, it.toString()) }
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
        map[KEY_LANGUAGE]?.let { p[keyLanguage] = it }
        map[KEY_WARN_MOBILE_NETWORK]?.toLooseBool()?.let { p[keyWarnMobileNetwork] = it }
        map[KEY_SHOW_FILE_TYPE_LABEL]?.toLooseBool()?.let { p[keyShowFileTypeLabel] = it }
        map[KEY_SHOW_ACCOUNT_BUTTON]?.toLooseBool()?.let { p[keyShowAccountButton] = it }
        map[KEY_AUTO_CHECK_DAYS]?.let { p[keyAutoCheckDays] = it }
        map[KEY_LAST_AUTO_CHECK_TIME]?.let { p[keyLastAutoCheckTime] = it }
        map[KEY_AUTO_LOAD]?.toLooseBool()?.let { p[keyAutoLoad] = it }
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
        const val KEY_LANGUAGE = "app_language"
        const val KEY_WARN_MOBILE_NETWORK = "warn_mobile_network"
        const val KEY_SHOW_FILE_TYPE_LABEL = "show_file_type_label"
        const val KEY_SHOW_ACCOUNT_BUTTON = "show_account_button"
        const val KEY_AUTO_CHECK_DAYS = "auto_check_favorites_time"
        const val KEY_LAST_AUTO_CHECK_TIME = "last_auto_check_time"
        const val KEY_AUTO_LOAD = "auto_load"
    }
}
