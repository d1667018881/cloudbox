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
     * 对齐原版 `show_file_type_label`（settings/customize_settings.lua
     * 「格式开关」/「格式文本」）。默认关闭——原版也是让用户自己去开；
     * 列表项本来就会显示完整文件名，后缀标签属于"更好看"而非"更必要"。
     */
    val showFileTypeLabel: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyShowFileTypeLabel] ?: false
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
}
