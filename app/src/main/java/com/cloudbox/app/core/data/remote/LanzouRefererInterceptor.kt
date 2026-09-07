package com.cloudbox.app.core.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上传/下载接口的 Referer 补全拦截器。
 *
 * 为什么必须有：蓝奏云 html5up.php 会校验 Referer，来源缺失或不当时**不会报错**，
 * 而是返回 {"zt":1,...} 却并不真正入库——这就是"上传显示成功、云端却找不到文件"
 * 的成因之一。实测（2026-09）与社区多份脚本一致：Referer 必须是网盘文件页
 * `https://<网盘域>/mydisk.php?item=files&action=index`（带 ylogin 时拼 &u=<ylogin>）。
 *
 * 只对上传接口（html5up.php / fileup.php）生效，且**不覆盖**调用方显式设置的 Referer。
 */
@Singleton
class LanzouRefererInterceptor @Inject constructor(
    private val domainInterceptor: LanzouDomainInterceptor,
    private val cookieJar: CookiePersistenceJar
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val isUpload = path.contains("/html5up.php") || path.contains("/fileup.php")
        if (!isUpload || request.header("Referer") != null) {
            return chain.proceed(request)
        }
        val disk = domainInterceptor.snapshot().diskMain.trimEnd('/')
        val ylogin = cookieJar.cookieValue("ylogin")
        val referer = buildString {
            append(disk).append("/mydisk.php?item=files&action=index")
            if (!ylogin.isNullOrBlank()) append("&u=").append(ylogin)
        }
        return chain.proceed(request.newBuilder().header("Referer", referer).build())
    }
}
