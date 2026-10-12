package com.cloudbox.app.common

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 第三方下载器（V56 重对齐蓝云 ty_core.lua「下载」函数三分支，4369-4539 行）。
 *
 * 蓝云原版语义（反编译源码逐行核对）：
 * ```
 * use_third_party_downloader == false →
 *     系统下载（内置队列 + 下载管理页）
 * use == true 且 custom_downloader == true →
 *     Intent().setAction(ACTION_SEND).setType(通配文本 MIME)
 *       .putExtra(EXTRA_TEXT, url).setClassName(pack, activity)
 *     pcall 失败 → 提示「自定义下载器打开失败，请在设置中重新设置」，不回落
 * use == true 且未配置 →
 *     浏览器打开 = Intent(ACTION_VIEW, Uri.parse(url))
 *     即**系统选择器**——设置帮助文案「每次下载都需询问选择下载器」的出处
 * ```
 *
 * V53-V55 的应用内选择弹窗（ACTION_SEND 反查 + 白名单过滤）是对齐失败的
 * 自造路线，V56 整体移除：未配置 = 系统选择器（原版同款），系统只列能打开
 * 网址的应用，天然没有「不相关应用」问题。
 *
 * 枚举语义（调用方分流用）：
 * - [SENT] 已交给配置的下载器 → 不进内置队列
 * - [CHOOSER] 未配置，已调起系统选择器 → 不进内置队列（用户取消=无事发生）
 * - [FAILED] 配了但打不开（没装/类名错）→ 提示改配置，不回落（防双下）
 */
enum class HandOffResult { SENT, CHOOSER, FAILED }

object ThirdPartyDownloader {

    /**
     * 把直链交给第三方下载器（蓝云原版「下载」函数 use==true 分支的等价实现）。
     *
     * @param pack     配置的下载器包名（空 = 走系统选择器，原版「浏览器打开」）
     * @param activity 配置的 Activity 全名（空 = 查该包能接 ACTION_SEND 的入口）
     */
    fun handOff(url: String, fileName: String, context: Context, pack: String, activity: String): HandOffResult {
        val p = pack.trim()
        if (p.isBlank()) {
            // 原版「浏览器打开」：ACTION_VIEW 让系统选择器问用户
            return try {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    // ApplicationContext 发 Activity intent 必须 NEW_TASK（V54 教训）
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(view)
                HandOffResult.CHOOSER
            } catch (e: Exception) {
                HandOffResult.FAILED
            }
        }
        return try {
            // 原版同款：ACTION_SEND 文本分享 + setClassName 强制显式组件
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/*"
                putExtra(Intent.EXTRA_TEXT, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val act = activity.trim()
            if (act.isNotBlank()) {
                intent.setClassName(p, act)
            } else {
                // 只配了包名：解析该包能接 ACTION_SEND 的入口，解析不到 = FAILED
                intent.setPackage(p)
                if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) {
                    return HandOffResult.FAILED
                }
            }
            context.startActivity(intent)
            HandOffResult.SENT
        } catch (e: Exception) {
            HandOffResult.FAILED
        }
    }
}
