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

    suspend fun setUserAgent(ua: String) = edit { p -> p[keyUserAgent] = ua }

    suspend fun setSuffixSpoof(enabled: Boolean) = edit { p -> p[keySuffixSpoof] = enabled }

    suspend fun setThirdPartyResolver(url: String) = edit { p -> p[keyThirdPartyResolver] = url }

    suspend fun setDarkMode(mode: String) = edit { p -> p[keyDarkMode] = mode }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }
}
