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
    private val keyThirdPartyResolver = stringPreferencesKey("third_party_resolver_url")
    private val keyDarkMode = stringPreferencesKey("dark_mode") // system / light / dark
    private val keyLanguage = stringPreferencesKey("app_language") // system / zh / en
    private val keyWarnMobileNetwork = booleanPreferencesKey("warn_mobile_network")

    /** 当前 UA：未自定义时返回默认桌面 UA（伪装关键） */
    val userAgent: Flow<String> = context.settingsDataStore.data.map {
        it[keyUserAgent] ?: AppConstants.DESKTOP_UA
    }

    val suffixSpoofEnabled: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keySuffixSpoof] ?: true // 默认开启：不支持格式伪装为 .zip 上传
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

    // 注：曾经有过 preferWebUpload（上传通道开关），2026-09-09 移除。
    // 上传一律走 App 原生直传——原版 App 就是这么做的，设置页的「上传通道自检」
    // 也实测证明这条路能真正上传成功。网页上传降级为设置页的一个手动入口，
    // 不再需要一个"默认通道"开关。

    suspend fun setUserAgent(ua: String) = edit { p -> p[keyUserAgent] = ua }

    suspend fun setSuffixSpoof(enabled: Boolean) = edit { p -> p[keySuffixSpoof] = enabled }

    suspend fun setThirdPartyResolver(url: String) = edit { p -> p[keyThirdPartyResolver] = url }

    suspend fun setDarkMode(mode: String) = edit { p -> p[keyDarkMode] = mode }

    suspend fun setAppLanguage(lang: String) = edit { p -> p[keyLanguage] = lang }

    suspend fun setWarnMobileNetwork(enabled: Boolean) = edit { p -> p[keyWarnMobileNetwork] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }
}
