package com.cloudbox.app.common

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo

/**
 * 第三方下载器候选过滤（V55）。
 *
 * 背景：ACTION_SEND + text/plain 的反向查询会列出**所有**能收文本的应用
 * （微信/QQ/笔记/浏览器…）——TA 真机实锤「弹出好多不相关的应用」。
 *
 * 策略（两级）：
 * 1. 已知下载器包名白名单（命中置顶）
 * 2. 包名或应用标签小写包含下载器关键词（download / adm / idm / aria2 …）
 *
 * 两级都不过的应用默认隐藏；UI 侧提供「显示全部」兜底（用户装的是冷门
 * 下载器时仍能选）。ACTION_SEND 是蓝云原版交互，查询本身不变，只变展示。
 */
object DownloaderCandidates {

    /** 已知下载器包名（命中即认为是下载器，置顶排序不变） */
    private val KNOWN_PACKS = setOf(
        "com.dv.adm",                        // ADM (Advanced Download Manager)
        "idm.internet.download.manager",      // 1DM
        "idm.internet.download.manager.plus",// 1DM+
        "org.zloy.android.downloader"        // LoaderDroid（老应用，留着无妨）
    )

    /** 包名/标签任一小写包含即认为像下载器 */
    private val KEYWORDS = listOf(
        "download", "downloader", "adm", "idm",
        "aria2", "torrent", "magnet", "motrix", "xdm"
    )

    /** 全量候选（ACTION_SEND text/plain 接收者，排除自己） */
    fun queryAll(context: Context): List<ResolveInfo> = runCatching {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_SEND).setType("text/plain")
        pm.queryIntentActivities(probe, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }.getOrDefault(emptyList())

    /** 是否像下载器（白名单 or 关键词） */
    fun isDownloaderish(ri: ResolveInfo, context: Context): Boolean {
        val pack = ri.activityInfo.packageName.lowercase()
        if (pack in KNOWN_PACKS) return true
        val label = runCatching {
            ri.loadLabel(context.packageManager).toString().lowercase()
        }.getOrDefault("")
        return KEYWORDS.any { it in pack } || KEYWORDS.any { it in label }
    }

    /** 默认视图：过滤后的候选。全量为空或过滤后为空都返回空列表（UI 自行兜底显示全量） */
    fun queryFiltered(context: Context): List<ResolveInfo> =
        queryAll(context).filter { isDownloaderish(it, context) }
}
