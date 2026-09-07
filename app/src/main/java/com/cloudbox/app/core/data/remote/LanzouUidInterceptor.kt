package com.cloudbox.app.core.data.remote

import com.cloudbox.app.core.data.local.secure.AccountSecureStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理接口 `doupload.php` 的 `?uid=` 自动注入拦截器。
 *
 * 为什么必须带：原版 App（逆向 home_func.lua:2308/2372/2463）在所有 doupload.php
 * 请求上都拼了 `?uid=<网盘数字 uid>`（变量 uid后缀），例如
 *   POST https://pc.woozooo.com/doupload.php?uid=1702063   task=47&folder_id=-1&pg=1
 * 该 uid 是服务端分配的**数字 id**，不是登录账号名。
 *
 * 本项目早前在 getDirList 上手动传了"登录账号名"作为 uid——服务端解析不了，
 * 子文件夹列表会拿到空结果。改为由本拦截器统一注入真实数字 uid：
 * - 只对 doupload.php 生效（其它接口不需要，带上反而可能触发风控）
 * - 已有 uid 查询参数时不覆盖（调用方显式指定优先）
 * - 未取到数字 uid 时原样放行（接口在无 uid 时仍可能工作，不阻断）
 */
@Singleton
class LanzouUidInterceptor @Inject constructor(
    private val accountStore: AccountSecureStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.contains("/doupload.php")) {
            return chain.proceed(request)
        }
        if (request.url.queryParameter("uid") != null) {
            return chain.proceed(request)
        }
        val uid = accountStore.currentUid() ?: return chain.proceed(request)
        val cloudUid = accountStore.cloudUid(uid)
        if (cloudUid.isNullOrBlank()) return chain.proceed(request)

        val newUrl = request.url.newBuilder()
            .setQueryParameter("uid", cloudUid)
            .build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
