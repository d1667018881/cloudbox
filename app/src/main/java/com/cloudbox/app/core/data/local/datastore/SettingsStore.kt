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
    private val keyPreferWebUpload = booleanPreferencesKey("prefer_web_upload")

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
     * 上传默认通道：true = 网页上传（官方页面），false = 原生直传（App 自己传）。
     *
     * **默认 false（原生直传）** —— 这才是 App 该有的形态：选完文件自动上传，
     * 不需要用户在网页里手动点一遍。
     *
     * V12 起原生直传已按原版 home.lua 逐字节复刻（只有 task / folder_id /
     * upload_file 三个字段，且每个字段都带 Content-Type 与 Content-Transfer-Encoding
     * 子头，见 UploadRepositoryImpl.buildOriginalMultipart）。之前"假成功"是协议
     * 写错导致的，不是原生路线本身不可行。
     *
     * 万一蓝奏云再改版导致原生失效，把这个开关打开即可临时切到网页通道。
     */
    val preferWebUpload: Flow<Boolean> = context.settingsDataStore.data.map {
        it[keyPreferWebUpload] ?: false
    }

    suspend fun setUserAgent(ua: String) = edit { p -> p[keyUserAgent] = ua }

    suspend fun setSuffixSpoof(enabled: Boolean) = edit { p -> p[keySuffixSpoof] = enabled }

    suspend fun setThirdPartyResolver(url: String) = edit { p -> p[keyThirdPartyResolver] = url }

    suspend fun setDarkMode(mode: String) = edit { p -> p[keyDarkMode] = mode }

    suspend fun setPreferWebUpload(enabled: Boolean) =
        edit { p -> p[keyPreferWebUpload] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }
}
