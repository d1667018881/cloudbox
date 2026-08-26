package com.cloudbox.app.common

import java.io.IOException

/**
 * 统一错误分类。
 *
 * 为什么：需求规格工程要求 2——每个网络请求必须有错误分类处理。
 * 把"HTTP 状态码/异常"翻译成"业务语义"，UI 层只关心业务语义：
 * - 403 → Cookie 过期（蓝奏云常见：phpdisk_info 失效后管理接口直接 403）
 * - 429 → 风控限流（访问过于频繁被临时拉黑）
 * - 网络异常 → 断网/DNS/超时
 */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Cookie 过期或未登录（HTTP 403） */
    data class CookieExpired(val detail: String? = null) : ApiError("Cookie 过期或未登录: $detail")

    /** 风控限流（HTTP 429） */
    data class RateLimited(val retryAfterSec: Long? = null) : ApiError("访问过于频繁，已被风控限流")

    /** 业务失败（HTTP 200 但接口返回 zt=0 / 状态码非 0） */
    data class Business(val code: Int, override val message: String) : ApiError(message)

    /** 网络层异常（DNS/连接超时/SSL/IO） */
    data class Network(val reason: String) : ApiError("网络异常: $reason")

    /** 服务器错误（5xx） */
    data class Server(val httpCode: Int) : ApiError("服务器错误[$httpCode]")

    /** 其他（4xx 非 403/429、解析失败等） */
    data class Unknown(val detail: String? = null) : ApiError("未知错误: $detail")

    companion object {
        /**
         * 把 OkHttp/Retrofit 的失败翻译为 [ApiError]。
         * 注意：Retrofit suspend 方法抛 HttpException；OkHttp 直连抛 IOException。
         */
        fun from(cause: Throwable, httpCode: Int? = null): ApiError = when {
            httpCode == 403 -> CookieExpired()
            httpCode == 429 -> RateLimited()
            httpCode != null && httpCode >= 500 -> Server(httpCode)
            cause is IOException -> Network(cause.message ?: "IO 异常")
            httpCode != null -> Unknown("HTTP $httpCode")
            else -> Unknown(cause.message)
        }
    }
}

/** 通用结果包装：省去每层手写 Result 转译 */
typealias AppResult<T> = Result<T>
