package com.cloudbox.app.core.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.core.domain.model.LanzouDomainConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.domainDataStore: DataStore<Preferences> by preferencesDataStore(name = "domain_config")

/**
 * 域名配置的本地持久层（DataStore）。
 *
 * 存储策略：只存"用户手动覆盖"的字段，未覆盖的字段保持默认。
 * 这样远程配置更新后，未覆盖字段自动跟随，只有用户明确改过的字段保持本地值。
 */
@Singleton
class DomainConfigStore @Inject constructor(private val context: Context) {

    private val keyLogin = stringPreferencesKey("loginEntry")
    private val keyDisk = stringPreferencesKey("diskMain")
    private val keyShare = stringPreferencesKey("shareBase")
    private val keyUpload = stringPreferencesKey("uploadServer")
    private val keyFallback = stringPreferencesKey("fallbackDomains")
    private val keyRemoteUrl = stringPreferencesKey("remoteUrl")
    private val keyInitialized = stringPreferencesKey(AppConstants.DS_KEY_DOMAIN_INIT)

    /** 观察用户手动覆盖配置（未覆盖的字段为 null） */
    fun observeOverrides(): Flow<PartialDomainConfig> =
        context.domainDataStore.data.map { p ->
            PartialDomainConfig(
                loginEntry = p[keyLogin],
                diskMain = p[keyDisk],
                shareBase = p[keyShare],
                uploadServer = p[keyUpload],
                fallbackDomains = p[keyFallback]?.split("\n")?.filter { it.isNotBlank() },
                remoteUrl = p[keyRemoteUrl]
            )
        }

    suspend fun getOverrides(): PartialDomainConfig = observeOverrides().first()

    suspend fun saveOverrides(config: LanzouDomainConfig) {
        context.domainDataStore.edit { p ->
            p[keyLogin] = config.loginEntry
            p[keyDisk] = config.diskMain
            p[keyShare] = config.shareBase
            p[keyUpload] = config.uploadServer
            p[keyFallback] = config.fallbackDomains.joinToString("\n")
            p[keyInitialized] = "1"
        }
    }

    suspend fun saveRemoteUrl(url: String) {
        context.domainDataStore.edit { p -> p[keyRemoteUrl] = url }
    }

    suspend fun clearOverrides() {
        context.domainDataStore.edit { p ->
            p.remove(keyLogin); p.remove(keyDisk); p.remove(keyShare)
            p.remove(keyUpload); p.remove(keyFallback)
        }
    }
}

/** 部分覆盖配置：null 表示未覆盖，采用默认值 */
data class PartialDomainConfig(
    val loginEntry: String? = null,
    val diskMain: String? = null,
    val shareBase: String? = null,
    val uploadServer: String? = null,
    val fallbackDomains: List<String>? = null,
    val remoteUrl: String? = null
)
