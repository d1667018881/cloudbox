package com.cloudbox.app.common

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理（对齐原版 `error_page.lua`）。
 *
 * ─────────────────────────────────────────────────────────────
 * 为什么需要它
 * ─────────────────────────────────────────────────────────────
 * 原版 App 崩溃时会跳到一个「程序错误」页，把错误信息展示出来并允许复制，
 * 而不是直接闪退。对自维护项目来说这个能力尤其值钱：
 *
 * - 用户拿到的是 APK，没法看 logcat，"打开就闪退"等于完全无法排查；
 * - 崩溃信息留在页面上，可以直接截图/复制回来定位。
 *
 * ─────────────────────────────────────────────────────────────
 * 实现要点
 * ─────────────────────────────────────────────────────────────
 * 1. 保存原始 handler，处理完**转发**给它 —— 这样系统仍会走正常的进程终止流程，
 *    不会因为吞掉异常而让 App 处于不确定状态。
 * 2. 错误信息写进 [lastCrash]（进程内 StateFlow）+ 持久化到文件，
 *    冷启动时能读出来展示（崩溃当场没法渲染 UI，只能下次启动展示）。
 * 3. 用一个独立的 [ErrorActivity] 承载错误页，避免把崩溃状态绑进主 Activity 的
 *    导航图（崩在导航途中时再导航一次极易二次崩溃）。
 */
object CrashHandler {

    private const val TAG = "CloudBoxCrash"
    private const val PREFS = "cloudbox_crash"
    private const val KEY_LAST = "last_crash"

    private val _lastCrash = MutableStateFlow<String?>(null)

    /** 最近一次崩溃信息（进程内可订阅；冷启动时由 [readPersisted] 填充） */
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    private var installed = false

    /** 安装全局处理器；重复调用安全（只生效一次） */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val original = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val report = buildReport(thread, throwable)
            runCatching { persist(appContext, report) }
            _lastCrash.value = report
            // 尝试拉起错误页。失败也不影响后续转发（那才是主线）。
            runCatching { launchErrorPage(appContext, report) }
            // 转发给系统默认处理器：保持正常的崩溃退出语义
            original?.uncaughtException(thread, throwable)
        }
    }

    /** 读取上次崩溃（冷启动用） */
    fun readPersisted(context: Context): String? {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST, null)
        _lastCrash.value = v
        return v
    }

    /** 清空已记录的崩溃 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
        _lastCrash.value = null
    }

    private fun persist(context: Context, report: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST, report)
            .apply()
    }

    private fun launchErrorPage(context: Context, report: String) {
        val intent = Intent(context, ErrorActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            putExtra(ErrorActivity.EXTRA_ERROR, report)
        }
        context.startActivity(intent)
    }

    /** 拼一份可读的崩溃报告（含设备信息，方便判断环境相关性问题） */
    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        return buildString {
            append("时间：").append(time).append('\n')
            append("线程：").append(thread.name).append('\n')
            append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("系统：Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
            append("进程：").append(Process.myPid()).append('\n')
            append("\n──────── 异常堆栈 ────────\n")
            append(sw.toString())
        }
    }
}
