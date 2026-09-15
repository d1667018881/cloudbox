package com.cloudbox.app.feature.favorites

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.domain.repository.ShareRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * 收藏夹自动检查 Worker（V30，对齐原版 v1.3.4.9 的 `auto_check_favorites`）。
 *
 * ## 原版行为
 *
 * 原版在**每次启动应用**时比较"启动日期"与"上次检查日期"：
 * ```
 * 若 开关打开 且 (现在 - 上次检查) >= auto_check_favorites_time
 *     → 触发一次检查，并把上次检查日期更新为现在
 * ```
 * 也就是说它并不是真正的后台定时任务，而是**启动时的懒检查**。
 *
 * ## 这里为什么用 WorkManager 而不是照抄启动检查
 *
 * 启动检查有个实际缺陷：只在前台启动时才有机会跑，用户连着几天不打开
 * App 就永远不会检查——而这恰恰是"自动检查"最该发挥作用的场景。
 *
 * WorkManager 的 `PeriodicWorkRequest` 由系统在后台择机执行（无需 App 存活），
 * 语义上严格强于原版，且天然避开了"启动瞬间抢占主线程/网络"的问题。
 * 因此这里用**周期性任务**实现，间隔直接取用户设置的档位。
 *
 * 约束选 `UNMETERED`（不计费网络）而非 `CONNECTED`：
 * 检查别人网盘的文件夹列表会同步拉取每个文件夹的 HTML 页面，属于真实流量消耗。
 * 原版没有这个顾虑（它只在用户主动打开 App 时跑，且不下载文件），
 * 但自动化之后必须替用户把关——放到 WiFi 下跑是这里唯一合理的默认。
 *
 * ## 失败策略
 *
 * 一律返回 [Result.success]。检查失败（网络抖动、对方删档）不应让 WorkManager
 * 记一次失败——那会触发退避重试，而重试的代价是再次抓取对方页面。
 * 单次失败没有副作用，下一个周期自然会重来。
 */
@HiltWorker
class FavoriteUpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val shareRepository: ShareRepository,
    private val settingsStore: SettingsStore
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val days = settingsStore.autoCheckFavoritesDays.first()
        if (days <= 0) {
            // 用户在两次调度之间关掉了开关 —— 直接退出，不报错。
            // 调度本身会在下次保存设置时被取消，这里的判断是兜底。
            Log.i(TAG, "自动检查已关闭，跳过本次执行")
            return Result.success()
        }
        val summary = shareRepository.checkFolderUpdates().getOrNull()
        Log.i(
            TAG,
            "自动检查完成：共 ${summary?.checked ?: 0} 项，更新 ${summary?.updated ?: 0} 项，" +
                "失败 ${summary?.failed ?: 0} 项"
        )
        return Result.success()
    }

    companion object {
        private const val TAG = "CloudBoxFavCheck"
    }
}
