package com.cloudbox.app.core.data.remote

import com.cloudbox.app.BuildConfig
import com.cloudbox.app.common.AppConstants
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp + Retrofit 单例构建。
 *
 * 为什么所有请求共用同一个 OkHttpClient：
 * 1. UA 伪装、域名重写、Cookie 持久化、重试策略四个拦截器一次配置全局生效
 * 2. 连接池复用（keep-alive），批量解析直链时显著降低连接建立开销
 * 3. 直链下载等自定义 OkHttp 请求与 Retrofit 请求行为一致
 *
 * 注意：超时 30s（connect/read/write）是需求规格的硬性要求；
 * 大文件下载不走本 client（read 超时 30s 会中断长连接），走系统 DownloadManager（第三批）。
 */
@Singleton
class LanzouApiClient @Inject constructor(
    val domainInterceptor: LanzouDomainInterceptor,
    private val uaInterceptor: UserAgentInterceptor,
    private val retryInterceptor: RetryInterceptor,
    private val cookieJar: CookiePersistenceJar,
    private val settingsStore: SettingsStore
) {

    val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(AppConstants.TIMEOUT_CONNECT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AppConstants.TIMEOUT_READ_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(AppConstants.TIMEOUT_WRITE_MS, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .cookieJar(cookieJar)
            // 顺序说明：Application 拦截器按注册顺序执行。
            // 重试在最外层（重试整个请求链）→ UA → 域名重写（最内层，保证 cookieJar 看到真实 URL）
            .addInterceptor(retryInterceptor)
            .addInterceptor(uaInterceptor)
            .addInterceptor(domainInterceptor)
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }
        builder.build()
    }

    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://${AppConstants.PLACEHOLDER_HOST}/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val apiService: LanzouApiService by lazy { retrofit.create(LanzouApiService::class.java) }

    /** 启动时调用：订阅设置里的自定义 UA，热更新到拦截器（无需重建 client） */
    fun bindSettings(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            settingsStore.userAgent.collect { uaInterceptor.userAgent = it }
        }
    }
}
