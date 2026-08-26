package com.cloudbox.app.core.data.remote

import com.cloudbox.app.common.AppConstants
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UA 伪装拦截器。
 *
 * 为什么是核心：蓝奏云对手机端 UA 隐藏 APK/exe 等格式的下载入口，
 * 判定依据就是 User-Agent（需求规格项目背景）。本 App 全程使用桌面 Chrome UA，
 * 才能拿到完整下载入口。
 *
 * Referer 默认不带（各请求按需由上层设置）；UA 通过 volatile 变量支持运行时热更新
 * （设置页自定义 UA 后无需重启 App）。
 */
@Singleton
class UserAgentInterceptor @Inject constructor() : Interceptor {

    @Volatile
    var userAgent: String = AppConstants.DESKTOP_UA

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
            .newBuilder()
            .header("User-Agent", userAgent)
            .build()
        return chain.proceed(request)
    }
}
