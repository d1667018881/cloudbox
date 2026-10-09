package com.cloudbox.app.common

import android.content.Context
import android.content.Intent

/**
 * 第三方下载器（V52，对齐蓝云 ty_core.lua:4524）。
 *
 * 蓝云原版语义：
 * ```
 * Intent().setAction(ACTION_SEND).setType("text/*")
 *   .putExtra(EXTRA_TEXT, url)
 *   .setClassName(custom_downloader_pack, custom_downloader_activity)
 * ```
 * 关键差异（对照 V49 的错误实现）：
 * 1. **ACTION_SEND + EXTRA_TEXT**，不是 ACTION_VIEW + octet-stream ——
 *    ACTION_VIEW 空 intent 会被系统下载器/浏览器直接接管（TA 真机看到的
 *    「直接调用的是系统下载根本没有选项」就是它）
 * 2. **setClassName 强制包名+Activity**，没有「都空走系统选择器」的路径
 *    ——蓝云配不齐就是不发
 * 3. 发送失败只提示「请在设置中重新设置」，**绝不回落内置队列**——
 *    开了第三方还双轨下载（TA 怀疑「下了两次」）是 ACTION_VIEW 语义的锅
 *
 * 枚举语义（调用方分流用）：
 * - [NOT_CONFIGURED] 没配包名 → 回落内置队列（附提示）
 * - [SENT] 已成功交给下载器 → 不进内置队列
 * - [FAILED] 配了但打不开（没装/类名错）→ 提示改配置，不回落（防双下）
 */
enum class HandOffResult { NOT_CONFIGURED, SENT, FAILED }

object ThirdPartyDownloader {

    /**
     * 把直链交给用户配置的第三方下载器（ADM/1DM 等）。
     * @param pack     配置的下载器包名（空 = NOT_CONFIGURED，调用方回落内置）
     * @param activity 配置的 Activity 全名（空 = 查该包能接 ACTION_SEND 的主入口）
     */
    fun handOff(url: String, fileName: String, context: Context, pack: String, activity: String): HandOffResult {
        val p = pack.trim()
        if (p.isBlank()) return HandOffResult.NOT_CONFIGURED
        return try {
            // 蓝云同款：ACTION_SEND 文本分享（绝大多数下载器都注册了这个入口）
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_TITLE, fileName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val act = activity.trim()
            if (act.isNotBlank()) {
                // 蓝云同款：setClassName 强制显式组件
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
