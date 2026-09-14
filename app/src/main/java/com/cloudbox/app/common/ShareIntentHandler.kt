package com.cloudbox.app.common

import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 解析「从其他 App 分享进来」的 Intent。
 *
 * ─────────────────────────────────────────────────────────────
 * 为什么单独成类
 * ─────────────────────────────────────────────────────────────
 * 分享进来的 intent 有**三种完全不同的形态**，混在 Activity 里判断
 * 很容易漏掉分支（原版 Lua 在 `home.lua` 的 `intent操作` 里就是一大坨 if）：
 *
 * | 来源 | action | 数据在哪 |
 * |---|---|---|
 * | 分享单个文件 | `ACTION_SEND` | `EXTRA_STREAM`（Uri） |
 * | 分享多个文件 | `ACTION_SEND_MULTIPLE` | `EXTRA_STREAM`（ArrayList&lt;Uri&gt;） |
 * | 分享文本/链接 | `ACTION_SEND` | `EXTRA_TEXT`（String） |
 *
 * 还有两个坑要处理：
 * 1. **部分 App 同时发 TEXT 和 STREAM**（分享图文时），必须优先取文件；
 * 2. **`EXTRA_STREAM` 的类型可能是 Parcelable 也可能是 ArrayList**，
 *    版本/实现不一致，得两种都试。
 *
 * 抽成独立的纯函数（无 Android 状态依赖），便于单测与维护。
 * 参考实现：原版 `home.lua` 的 `intent操作`（proto[80]）。
 */
object ShareIntentHandler {

    private const val TAG = "CloudBoxUpload"

    /** 一次分享进来被识别出来的内容 */
    data class SharedContent(
        /** 分享进来的文件（可能多个）；与 [text] 互斥，优先非空 */
        val fileUris: List<Uri> = emptyList(),
        /** 分享进来的文本（多为蓝奏云分享链接） */
        val text: String? = null
    ) {
        val isEmpty: Boolean get() = fileUris.isEmpty() && text.isNullOrBlank()
        val hasFiles: Boolean get() = fileUris.isNotEmpty()
    }

    /**
     * 解析 intent。非分享类 intent 返回 [SharedContent] 空对象（调用方判 [SharedContent.isEmpty]）。
     */
    fun parse(intent: Intent?): SharedContent {
        if (intent == null) return SharedContent()
        val action = intent.action ?: return SharedContent()

        val files = when (action) {
            Intent.ACTION_SEND -> extractSingleStream(intent)
            Intent.ACTION_SEND_MULTIPLE -> extractMultipleStreams(intent)
            else -> emptyList()
        }

        // 文件优先：部分 App 分享图文时会同时塞 TEXT，此时 TEXT 往往是配文而非链接，
        // 拿去解析只会干扰。有文件就只认文件。
        if (files.isNotEmpty()) {
            Log.i(TAG, "分享进来 ${files.size} 个文件：${files.joinToString { it.lastPathSegment ?: it.toString() }}")
            return SharedContent(fileUris = files)
        }

        val text = runCatching { intent.getStringExtra(Intent.EXTRA_TEXT) }.getOrNull()
        if (!text.isNullOrBlank()) {
            Log.i(TAG, "分享进来文本（长度 ${text.length}）")
            return SharedContent(text = text)
        }
        return SharedContent()
    }

    /**
     * `ACTION_SEND` 的单个文件。
     *
     * `getParcelableExtra` 在 API 33+ 要传类型参数，低版本用旧签名；
     * 这里统一走兼容写法，避免 Build.VERSION 分支散落各处。
     */
    private fun extractSingleStream(intent: Intent): List<Uri> {
        val uri = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }.getOrNull()
        return listOfNotNull(uri)
    }

    /**
     * `ACTION_SEND_MULTIPLE` 的多个文件。
     *
     * 实测坑：`EXTRA_STREAM` 的类型标注是 `ArrayList<Uri>`，但有的 App 塞
     * 的是 `ParcelableArrayList`、甚至单个 Uri。逐级降级试，全试完才放弃。
     */
    private fun extractMultipleStreams(intent: Intent): List<Uri> {
        // 形态 1：标准 ArrayList<Uri>
        runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }.getOrNull()?.let { if (it.isNotEmpty()) return it.filterNotNull() }

        // 形态 2：单个 Uri（不规范实现，但确实遇到过）
        extractSingleStream(intent).let { if (it.isNotEmpty()) return it }

        // 形态 3：ClipData（拖拽/部分文件管理器用这个通道传多选）
        val clip = runCatching { intent.clipData }.getOrNull()
        if (clip != null) {
            val out = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /**
     * 分享进来的 URI 是否需要申请**持久读权限**。
     *
     * ⚠️ 这一步不能省：上传是交给 WorkManager 在**后台**做的，那时
     * Activity 的临时授权可能已失效，Worker 打开文件流会拿到
     * `SecurityException` → 表现为"上传失败但看不出原因"。
     *
     * 只有 `content://` 需要（`file://` 是直接路径，不涉及授权）。
     * 申请失败不致命（部分 provider 不支持持久授权），调用方当尽力而为。
     *
     * @return 需要申请的 URI 列表（可能为空）
     */
    fun urisNeedingPersistablePermission(uris: List<Uri>): List<Uri> =
        uris.filter { it.scheme == "content" }
}
