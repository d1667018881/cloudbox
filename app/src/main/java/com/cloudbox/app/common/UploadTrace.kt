package com.cloudbox.app.common

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上传链路的结构化日志记录器（**全局单例**）。
 *
 * ─────────────────────────────────────────────────────────────
 * 为什么要独立成一个单例，而不是放在 UploadViewModel 里
 * ─────────────────────────────────────────────────────────────
 * 上传链路是"五段式"的，出问题时要还原整条时间线才能定位：
 *
 * ```
 * ViewModel 建任务 → WorkManager 调度 → Worker 执行 → 回写 outputData → ViewModel 汇总
 * ```
 *
 * 前四段发生在 **UploadViewModel** 里，但第五段（Worker 侧）发生在
 * **UploadWorker** 里，而用户查看日志的入口可能在**设置页**（另一个 ViewModel）。
 * 如果日志挂在 UploadViewModel 上：
 * - Worker 要写日志就得反查 ViewModel（不可行，Worker 没有 UI 作用域）；
 * - 设置页拿不到网盘页那个 ViewModel 实例，看不到日志；
 * - ViewModel 随页面销毁重建，日志会丢。
 *
 * 所以做成 Hilt 单例：谁都能写、谁都能读，生命周期跟随进程。
 *
 * ─────────────────────────────────────────────────────────────
 * 判读方法（这是本类存在的意义）
 * ─────────────────────────────────────────────────────────────
 * 对照上传时间线，按下面的规则可以直接定位故障段：
 *
 * | 观察 | 结论 |
 * |---|---|
 * | 没有「已拷贝…准备入队」 | 卡在 SAF 读取阶段（文件没读到 / 空文件） |
 * | 有拷贝但无「任务已入队」 | 入队前抛异常（见同期的「入队失败」行） |
 * | ENQUEUED 后**直接** FAILED，无 RUNNING，runAttemptCount=0 | **Worker 没被构造** —— 工厂/注解处理器问题 |
 * | 有 RUNNING + 服务端回包 | 链路通，问题在服务端判定或目标目录 |
 * | 回包 zt=0 且 info 是中文原因 | 服务端明确拒绝（如「不能上传.格式的文件」） |
 *
 * @see UploadProbeResult 诊断结果（含 HTTP 码 / 耗时 / 原始回包）
 */
@Singleton
class UploadTrace @Inject constructor() {

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** 供 UI 收集展示（设置页、网盘页失败详情弹窗都用它） */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 记一行：同时进 logcat 与内存时间线 */
    fun log(line: String) {
        Log.i(TAG, line)
        _lines.update { (it + "${stamp.format(Date())}  $line").takeLast(MAX_LINES) }
    }

    /** 只进 logcat 不进时间线（噪音较大、排障时才看的内容） */
    fun debug(line: String) = Log.i(TAG, line)

    /** 取当前快照（复制出去，避免调用方持有内部列表） */
    fun snapshot(): List<String> = _lines.value

    /** 清空（设置页「清空日志」用） */
    fun clear() {
        _lines.value = emptyList()
    }

    companion object {
        /** logcat 过滤：adb logcat -s CloudBoxUpload */
        const val TAG = "CloudBoxUpload"

        /** 上限：长会话也不至于吃内存 */
        private const val MAX_LINES = 200
    }
}
