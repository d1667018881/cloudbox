package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.LanzouDomainConfig
import kotlinx.coroutines.flow.Flow

/** 单个域名的连通性测试结果（毫秒） */
data class DomainLatency(val url: String, val rttMs: Long?, val error: String? = null)

/**
 * 域名配置仓库（两层配置源）：
 * - 本地：DataStore 存用户手动覆盖
 * - 远程：启动拉取 JSON（GitHub Gist / 任意 HTTPS），失败回落本地默认值
 */
interface DomainRepository {
    /** 当前生效配置（合并本地覆盖后的结果），UI 与网络层都订阅它 */
    val domainConfig: Flow<LanzouDomainConfig>

    /** 一次性取当前值（供拦截器同步读取） */
    suspend fun current(): LanzouDomainConfig

    /** 用户手动覆盖全部字段 */
    suspend fun saveLocal(config: LanzouDomainConfig)

    /** 重置为用户手动配置（清空覆盖） */
    suspend fun resetLocal()

    /** 从远程 URL 拉取并应用；失败返回错误信息 */
    suspend fun fetchAndApplyRemote(remoteUrl: String): Result<LanzouDomainConfig>

    /** 并发 HEAD 测连通性，返回按 RTT 升序的结果 */
    suspend fun testConnectivity(config: LanzouDomainConfig): List<DomainLatency>
}
