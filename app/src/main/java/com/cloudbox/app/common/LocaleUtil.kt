package com.cloudbox.app.common

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言与网络环境小工具。
 *
 * 两件事放一起的原因：都只有几行，且都是"设置页/下载前"要用的环境判断，
 * 单独开两个文件反而增加查找成本。
 */
object LocaleUtil {

    /** 支持的语言选项：设置值 → 展示名 */
    val LANGUAGES = listOf(
        "system" to "跟随系统",
        "zh" to "简体中文",
        "en" to "English"
    )

    /**
     * 语言代码 → [Locale]。`system` 返回 null（表示不覆盖，用系统默认）。
     */
    fun localeOf(code: String): Locale? = when (code) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        else -> null
    }

    /**
     * 给 Context 套上指定的语言配置。
     *
     * ⚠️ 为什么用 `createConfigurationContext` 而不是 AppCompatDelegate
     * 或 `LocaleManager.setApplicationLocales`：
     * 本项目是纯 ComponentActivity + Compose，没有 AppCompat；而
     * `LocaleManager` 要 API 33+（本项目 minSdk 26）。
     * `createConfigurationContext` 是 API 17+ 的通用做法，虽然需要调用方
     * 每个 Activity 都套一层，但行为可预期、没有隐藏的时序问题。
     */
    fun wrap(context: Context, languageCode: String): Context {
        val locale = localeOf(languageCode) ?: return context
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }
}

/** 网络环境判断（移动网络提醒用） */
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
