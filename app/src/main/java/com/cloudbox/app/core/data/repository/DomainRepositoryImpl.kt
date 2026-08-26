package com.cloudbox.app.core.data.repository

import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.common.DomainUtils
import com.cloudbox.app.core.data.local.datastore.DomainConfigStore
import com.cloudbox.app.core.data.local.datastore.PartialDomainConfig
import com.cloudbox.app.core.data.remote.LanzouDomainInterceptor
import com.cloudbox.app.core.data.remote.LanzouApiClient
import com.cloudbox.app.core.data.remote.RemoteDomainSource
import com.cloudbox.app.core.domain.model.LanzouDomainConfig
import com.cloudbox.app.core.domain.repository.DomainLatency
import com.cloudbox.app.core.domain.repository.DomainRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 域名配置仓库实现。
 *
 * 配置合并优先级（高→低）：
 * 1. 用户手动覆盖（DataStore，仅覆盖非空字段）
 * 2. 远程配置（启动时拉取一次，应用到"未覆盖字段"）
 * 3. 内置默认值
 *
 * 为什么远程配置不直接覆盖本地值：用户手动改过的域名（比如运营商 DNS 污染
 * 后自行换域）是最高优先级，远程更新不该冲掉用户的选择。
 */
@Singleton
class DomainRepositoryImpl @Inject constructor(
    private val store: DomainConfigStore,
    private val remoteSource: RemoteDomainSource,
    private val apiClient: LanzouApiClient,
    private val domainInterceptor: LanzouDomainInterceptor
) : DomainRepository {

    private val _domainConfig: Flow<LanzouDomainConfig> =
        store.observeOverrides().map { overrides ->
            merge(LanzouDomainConfig.DEFAULT, overrides)
        }

    // 注意：必须放在 _domainConfig 声明之后，否则 init 访问未初始化属性
    override val domainConfig: Flow<LanzouDomainConfig> = _domainConfig.also {
        domainInterceptor.bindConfigFlow(_domainConfig)
    }

    override suspend fun current(): LanzouDomainConfig = domainConfig.first()

    override suspend fun saveLocal(config: LanzouDomainConfig) = store.saveOverrides(config)

    override suspend fun resetLocal() = store.clearOverrides()

    override suspend fun fetchAndApplyRemote(remoteUrl: String): Result<LanzouDomainConfig> {
        store.saveRemoteUrl(remoteUrl)
        return remoteSource.fetch(remoteUrl).onSuccess { remote ->
            // 把远程配置写入本地（作为"未覆盖基线"），
            // 用户手动覆盖的字段在 merge 时仍会优先
            val overrides = store.getOverrides()
            val merged = merge(remote, overrides)
            store.saveOverrides(merged)
        }
    }

    override suspend fun testConnectivity(config: LanzouDomainConfig): List<DomainLatency> =
        withContext(Dispatchers.IO) {
            // 收集所有候选域并去重：分享域 + 备用池 + 管理域 + 上传域
            val candidates = linkedSetOf<String>().apply {
                add(config.shareBase)
                add(config.diskMain)
                add(config.loginEntry)
                add(config.uploadServer)
                addAll(config.fallbackDomains)
            }
            coroutineScope {
                // 并发 HEAD 测 RTT（需求规格：并发 HEAD 请求）
                candidates.map { base ->
                    async { measure(base) }
                }.map { it.await() }
            }.sortedBy { it.rttMs ?: Long.MAX_VALUE }
        }

    private fun measure(base: String): DomainLatency {
        val normalized = DomainUtils.normalize(base)
        if (DomainUtils.isForbidden(normalized)) {
            return DomainLatency(normalized, null, "黑名单域名（lanzous.com 被抢注）")
        }
        val start = System.nanoTime()
        return try {
            val request = Request.Builder()
                .url(normalized)
                .header("User-Agent", AppConstants.DESKTOP_UA)
                .method("HEAD", null)
                .build()
            apiClient.okHttpClient.newCall(request).execute().use { resp ->
                val rttMs = (System.nanoTime() - start) / 1_000_000
                // 部分服务器禁止 HEAD（返回 405），此时 RTT 仍有效
                DomainLatency(normalized, rttMs, if (resp.code == 405) "HEAD 不支持" else null)
            }
        } catch (e: Exception) {
            DomainLatency(normalized, null, e.message)
        }
    }

    /** 合并：本地覆盖字段优先于基线（远程或默认） */
    private fun merge(base: LanzouDomainConfig, overrides: PartialDomainConfig): LanzouDomainConfig =
        LanzouDomainConfig(
            loginEntry = overrides.loginEntry?.let(DomainUtils::normalize) ?: base.loginEntry,
            diskMain = overrides.diskMain?.let(DomainUtils::normalize) ?: base.diskMain,
            shareBase = overrides.shareBase?.let(DomainUtils::normalize) ?: base.shareBase,
            uploadServer = overrides.uploadServer?.let(DomainUtils::normalize) ?: base.uploadServer,
            fallbackDomains = overrides.fallbackDomains
                ?.map(DomainUtils::normalize)
                ?.filter { !DomainUtils.isForbidden(it) }
                ?.ifEmpty { null }
                ?: base.fallbackDomains.filter { !DomainUtils.isForbidden(it) }
        )
}
