package com.cloudbox.app.common

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 剪贴板链接监听（需求规格 9 节）。
 *
 * 检测 lanzou.com / lanzoux.com / lanzoui.com / lanzoup.com / lanzouu.com
 * 等域名链接，弹窗提示"是否解析此链接"。
 *
 * 实现：Android 10+ 用 OnPrimaryClipChangedListener（系统回调）；
 * Android 10 以下回调不可靠，用 2s 轮询兜底。
 * 剪贴板内容变化极频繁，必须做"去重"——同一链接只提示一次。
 */
@Singleton
class ClipboardLinkWatcher @Inject constructor(
    private val context: Context
) {
    private val _pendingLink = MutableStateFlow<String?>(null)

    /** 待确认的分享链接（UI 收集后弹窗） */
    val pendingLink: StateFlow<String?> = _pendingLink.asStateFlow()

    private var lastDetected: String = ""
    private var pollJob: Job? = null
    private var listenerRegistered = false

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** 启动监听（MainActivity 调用一次） */
    fun start(scope: CoroutineScope) {
        if (!listenerRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                clipboard.addPrimaryClipChangedListener(clipListener)
                listenerRegistered = true
            }
        }
        // 低版本轮询兜底（低频率，省电）
        if (!listenerRegistered) {
            pollJob = scope.launch(Dispatchers.IO) {
                while (true) {
                    checkClipboard()
                    delay(2_000)
                }
            }
        }
    }

    fun stop() {
        if (listenerRegistered) {
            runCatching { clipboard.removePrimaryClipChangedListener(clipListener) }
            listenerRegistered = false
        }
        pollJob?.cancel()
    }

    /** 消费当前待确认链接（弹窗点击后调用） */
    fun consume(): String? {
        val link = _pendingLink.value
        _pendingLink.value = null
        return link
    }

    fun dismiss() {
        _pendingLink.value = null
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkClipboard()
    }

    private fun checkClipboard() {
        val text = runCatching {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull() ?: return
        // 去重：同一内容不重复提示
        if (text == lastDetected) return
        if (DomainUtils.isShareUrl(text)) {
            lastDetected = text
            _pendingLink.value = text
        }
    }
}
