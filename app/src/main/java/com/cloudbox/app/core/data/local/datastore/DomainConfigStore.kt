package com.cloudbox.app.core.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
 * 存储策略（审查 CODE_REVIEW #9 修复）：三层配置源各自独立存储、互不覆盖——
 * - 内置默认值（代码内 LanzouDomainConfig.DEFAULT）
 * - 远程配置（remote_* keys，启动时拉取 / 设置页手动拉取）
 * - 用户手动覆盖（loginEntry 等裸 key，设置页编辑）
 * 合并优先级：DEFAULT < remote < userOverride。
 *
 * 旧实现把"远程合并结果"整体写进 overrides 槽位，导致第二次远程更新时
 * 旧远程值变成最高优先级的"本地覆盖"，新远程值永远被压住（热更新自废武功）。
 */
@Singleton
class DomainConfigStore @Inject constructor(private val context: Context) {

    // 用户手动覆盖（优先级最高）
    private val keyLogin = stringPreferencesKey("loginEntry")
    private val keyDisk = stringPreferencesKey("diskMain")
    private val keyShare = stringPreferencesKey("shareBase")
    private val keyUpload = stringPreferencesKey("uploadServer")
    private val keyFallback = stringPreferencesKey("fallbackDomains")
    // 远程配置（优先级居中）
    private val keyRemoteLogin = stringPreferencesKey("remote_loginEntry")
    private val keyRemoteDisk = stringPreferencesKey("remote_diskMain")
    private val keyRemoteShare = stringPreferencesKey("remote_shareBase")
    private val keyRemoteUpload = stringPreferencesKey("remote_uploadServer")
    private val keyRemoteFallback = stringPreferencesKey("remote_fallbackDomains")
    private val keyRemoteUrl = stringPreferencesKey("remoteUrl")

    /** 观察用户手动覆盖配置（未覆盖的字段为 null） */
    fun observeOverrides(): Flow<PartialDomainConfig> =
        context.domainDataStore.data.map { p ->
            PartialDomainConfig(
                loginEntry = p[keyLogin],
                diskMain = p[keyDisk],
                shareBase = p[keyShare],
                uploadServer = p[keyUpload],
                fallbackDomains = p[keyFallback]?.split("\n")?.filter { it.isNotBlank() }
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
        }
    }

    suspend fun clearOverrides() {
        context.domainDataStore.edit { p ->
            p.remove(keyLogin); p.remove(keyDisk); p.remove(keyShare)
            p.remove(keyUpload); p.remove(keyFallback)
        }
    }

    /** 观察远程配置（未拉取过为 null） */
    fun observeRemote(): Flow<LanzouDomainConfig?> =
        context.domainDataStore.data.map { p ->
            val remote = LanzouDomainConfig(
                loginEntry = p[keyRemoteLogin] ?: return@map null,
                diskMain = p[keyRemoteDisk] ?: return@map null,
                shareBase = p[keyRemoteShare] ?: return@map null,
                uploadServer = p[keyRemoteUpload] ?: return@map null,
                fallbackDomains = p[keyRemoteFallback]?.split("\n")?.filter { it.isNotBlank() }
                    ?: return@map null
            )
            remote
        }

    /** 保存远程配置（独立槽位，不碰用户覆盖） */
    suspend fun saveRemote(config: LanzouDomainConfig) {
        context.domainDataStore.edit { p ->
            p[keyRemoteLogin] = config.loginEntry
            p[keyRemoteDisk] = config.diskMain
            p[keyRemoteShare] = config.shareBase
            p[keyRemoteUpload] = config.uploadServer
            p[keyRemoteFallback] = config.fallbackDomains.joinToString("\n")
        }
    }

    suspend fun getRemoteUrl(): String = context.domainDataStore.data.first()[keyRemoteUrl] ?: ""

    suspend fun saveRemoteUrl(url: String) {
        context.domainDataStore.edit { p -> p[keyRemoteUrl] = url }
    }
}

/** 部分覆盖配置：null 表示未覆盖，采用默认值 */
data class PartialDomainConfig(
    val loginEntry: String? = null,
    val diskMain: String? = null,
    val shareBase: String? = null,
    val uploadServer: String? = null,
    val fallbackDomains: List<String>? = null
)
