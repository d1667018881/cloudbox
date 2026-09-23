package com.cloudbox.app.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络环境小工具。
 *
 * ⚠️ 2026-09-23 移除了「应用内语言」相关代码（原来的 `LANGUAGES` / `localeOf`
 * / `wrap`）：那套东西依赖 `values-xx/strings.xml` 里的词表，而本项目的
 * strings.xml 里**只有 app_name 一条** —— 界面文案全部硬编码在 Kotlin 里。
 * 也就是说设置页那个「English」选项点了界面照样是中文，是个**假功能**
 * （与原版被删掉的「多语言空壳」是同一个毛病）。老板拍板：语言跟随系统、
 * 保留中文即可。于是整个语言覆盖能力连同设置页选项一起删掉，而不是留一个
 * 不生效的开关骗人。
 *
 * 备注：文件名仍叫 LocaleUtil.kt（Kotlin 按类名解析，不影响引用），
 * 但里面只剩 [NetworkUtil]。
 */
object NetworkUtil {

    /** 当前是否在移动数据网络（蜂窝）上 */
    fun isOnMobileData(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            // 有蜂窝传输能力、且没有 WiFi 传输能力 → 是移动数据
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }.getOrDefault(false)
    }
}
