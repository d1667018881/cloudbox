package com.cloudbox.app.core.data.remote

import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.common.DomainUtils
import com.cloudbox.app.core.domain.model.LanzouDomainConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 域名动态重写拦截器 —— 工程要求 3 的落实点。
 *
 * 为什么必须重写 URL 而不是在 Retrofit 里写死域名：
 * 蓝奏云域名会漂移（lanzou.com → lanzoux → lanzoui → lanzoup → lanzouu → lanzouo → lanzouh…），
 * 且支持远程更新 + 用户手动覆盖。Retrofit 创建时绑定的 baseUrl 无法热更新，
 * 因此所有接口的 baseUrl 统一写占位 host（[AppConstants.PLACEHOLDER_HOST]），
 * 由本拦截器在请求发出前按"路径角色"映射到 [LanzouDomainConfig] 的当前值。
 *
 * 角色判定规则（woozooo 域名体系的路径规律）：
 * - 含 /fileup.php → 上传域 uploadServer
 * - 含 ajaxm → 分享域 shareBase（直链解析部署在分享域上）
 * - 其余 .php 管理接口（doupload/filemoreajax/mydisk/login）→ 管理域 diskMain
 * - 分享页 HTML → 分享域 shareBase
 *
 * 直链下载请求（host 为解析出的动态 dom，如 xxx.lanzoux.com）不受影响：
 * 只有 host 等于占位符的请求才被重写。
 */
@Singleton
class LanzouDomainInterceptor @Inject constructor() : Interceptor {

    /** 当前生效配置的同步缓存：拦截器运行在网络线程，不能 suspend 读 DataStore */
    private val currentConfig = AtomicReference(LanzouDomainConfig.DEFAULT)

    /** 由 DomainRepositoryImpl 注入 Flow 持续同步（见其 init） */
    fun bindConfigFlow(flow: Flow<LanzouDomainConfig>) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            flow.collectLatest { currentConfig.set(it) }
        }
    }

    /** 供直链解析等场景读取当前配置 */
    fun snapshot(): LanzouDomainConfig = currentConfig.get()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != AppConstants.PLACEHOLDER_HOST) {
            // 直链域/第三方解析服务的请求不重写
            return chain.proceed(request)
        }
        val config = currentConfig.get()
        val path = url.encodedPath

        // 角色判定（顺序敏感）：
        // 1. 上传接口 → uploadServer
        // 2. 直链解析 ajaxm.php → shareBase（ajaxm 部署在分享域上）
        // 3. 其余 .php 管理接口 → diskMain
        // 4. 分享页 HTML → shareBase
        val targetBase = when {
            path.contains("/fileup.php") -> config.uploadServer
            path.contains("ajaxm") -> config.shareBase
            path.endsWith(".php") -> config.diskMain
            else -> config.shareBase
        }

        val rewritten = rewrite(request, url, targetBase)
        return chain.proceed(rewritten)
    }

    private fun rewrite(request: okhttp3.Request, url: HttpUrl, base: String): okhttp3.Request {
        // OkHttp 4.x：HttpUrl.get(String) 已废弃（ERROR 级），改用 toHttpUrl 扩展
        val target = DomainUtils.normalize(base).toHttpUrl()
        val newUrl = url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        return request.newBuilder().url(newUrl).build()
    }
}
