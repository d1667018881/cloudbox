package com.cloudbox.app.core.data.remote

import com.cloudbox.app.common.AppConstants
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 指数退避重试拦截器（工程要求 2：最多 3 次重试）。
 *
 * 重试策略：
 * - 只对【网络异常】与【5xx / 429】重试。
 * - 403 绝不重试 —— 蓝奏云管理接口 403 = Cookie 过期，重试 N 次结果一样，
 *   只会浪费流量和时间，应直接抛给上层触发重新登录。
 * - 退避间隔：2s → 4s → 8s（指数），并附加 ±20% 随机抖动，
 *   避免多个请求同时重试造成自触发风控。
 *
 * 实现位置：必须注册为 Application Interceptor（而非 Network Interceptor），
 * 这样重试的是"整个请求"，连接失败/重定向都会正确重放。
 */
@Singleton
class RetryInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                // 网络异常：连接失败/超时/DNS
                if (attempt < AppConstants.MAX_RETRIES) {
                    sleepBackoff(attempt)
                    attempt++
                    continue
                }
                throw e
            }
            val code = response.code
            if ((code == 429 || code >= 500) && attempt < AppConstants.MAX_RETRIES) {
                // 429 限流 / 5xx 服务器错误：可重试
                response.close()
                sleepBackoff(attempt)
                attempt++
                continue
            }
            return response
        }
    }

    private fun sleepBackoff(attempt: Int) {
        // 2^attempt * 2s，加 ±20% 抖动：2s / 4s / 8s
        val base = AppConstants.RETRY_BASE_DELAY_MS * (1L shl attempt)
        val jitter = (base * 0.2 * (Math.random() * 2 - 1)).toLong()
        try {
            Thread.sleep((base + jitter).coerceAtLeast(500L), 0)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
