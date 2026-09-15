package com.cloudbox.app.feature.favorites

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 收藏夹自动检查的调度入口（V30）。
 *
 * 调度策略：**单一唯一任务**（[WORK_NAME]），每次用户改动设置都整体替换。
 *
 * 为什么不区分"开"和"关"两个动作：`days <= 0` 时就 cancel，
 * `days > 0` 时就 enqueue/update。用一个入口函数表达，调用方不需要
 * 记得"改小间隔要先取消旧的"，避免出现两个周期任务并存、检查频率翻倍。
 *
 * [ExistingPeriodicWorkPolicy.UPDATE] 而不是 `CANCEL_AND_REENQUEUE`：
 * UPDATE 会保留已有的调度基点，把新的间隔应用上去；
 * CANCEL + REENQUEUE 会把"下次执行时间"重置为 now + interval，
 * 用户在档位之间来回切换就可能无限推迟首次执行。
 */
object FavoriteUpdateCheckScheduler {

    private const val WORK_NAME = "cloudbox_favorite_update_check"
    private const val TAG = "CloudBoxFavCheck"

    /**
     * 按当前设置同步调度状态。设置页每次保存间隔后调用一次即可。
     *
     * @param days 间隔天数；<= 0 表示关闭（取消已有任务）
     */
    fun sync(context: Context, days: Int) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull()
        if (wm == null) {
            Log.e(TAG, "WorkManager 不可用，跳过自动检查调度")
            return
        }
        if (days <= 0) {
            runCatching { wm.cancelUniqueWork(WORK_NAME) }
            Log.i(TAG, "自动检查已关闭")
            return
        }
        val request = PeriodicWorkRequestBuilder<FavoriteUpdateCheckWorker>(
            days.toLong(), TimeUnit.DAYS
        )
            // 只在计费网络之外跑：检查会拉取每个收藏文件夹的页面，是真实流量
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()
        runCatching {
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }.onFailure {
            Log.e(TAG, "调度自动检查失败：${it.javaClass.simpleName} ${it.message}")
        }
        Log.i(TAG, "自动检查已开启，间隔 $days 天")
    }

    /**
     * 应用启动时按已保存的设置对齐一次调度。
     *
     * 必要性：`PeriodicWorkRequest` 会跨进程持久化，但用户**卸载重装/清数据**
     * 或者系统在极端低电模式下清理任务后，调度可能已经消失而设置仍在。
     * 启动时无脑 sync 一次（幂等）比等用户去设置页拨一下开关可靠得多。
     */
    fun syncFromSettings(context: Context, days: Int) = sync(context, days)
}
