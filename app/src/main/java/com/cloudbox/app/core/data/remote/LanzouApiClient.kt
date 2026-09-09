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
    private val refererInterceptor: LanzouRefererInterceptor,
    private val uidInterceptor: LanzouUidInterceptor,
    val cookieJar: CookiePersistenceJar,
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
            // 重试（最外层，重试整条链）→ UA → Referer 补全 → 域名重写（最内层，
            // 保证 cookieJar 与 Referer 拼装看到的是真实 URL）
            .addInterceptor(retryInterceptor)
            .addInterceptor(uaInterceptor)
            .addInterceptor(refererInterceptor)
            .addInterceptor(uidInterceptor)
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

    /**
     * 上传专用 OkHttpClient：只放宽读/写超时，其余（拦截器、连接池、Cookie）全量复用。
     *
     * 为什么必须单独开一个：默认 client 的 `writeTimeout` 是 30s（需求规格对
     * 普通 API 的要求），但上传是**长时间持续写入**——30s 只够传二三十 MB，
     * 大文件必然被掐断，表现为"传了半天最后失败，云端也没有文件"。
     * OkHttp 的超时绑在 client 上、无法按单个请求覆盖，所以只能另建实例。
     *
     * 用 `newBuilder()` 派生：连接池、拦截器、CookieJar 全部继承，
     * 额外成本只有一个 client 实例，不破坏 keep-alive 复用。
     */
    val uploadOkHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(AppConstants.TIMEOUT_UPLOAD_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(AppConstants.TIMEOUT_UPLOAD_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 上传专用 Retrofit：同一个 interface，只换了 client */
    val uploadApiService: LanzouApiService by lazy {
        retrofit.newBuilder().client(uploadOkHttpClient).build()
            .create(LanzouApiService::class.java)
    }

    /** 启动时调用：订阅设置里的自定义 UA，热更新到拦截器（无需重建 client） */
    fun bindSettings(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            settingsStore.userAgent.collect { uaInterceptor.userAgent = it }
        }
    }
}
