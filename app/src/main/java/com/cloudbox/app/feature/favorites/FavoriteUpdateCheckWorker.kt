package com.cloudbox.app.feature.favorites

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.domain.repository.ShareRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * 收藏夹自动检查 Worker（V30）。
 *
 * ## 原版 v1.3.4.9 的真实语义（home.lua:443-457）
 *
 * 原版**不是**后台定时任务，而是"启动时懒检查"：
 * ```lua
 * if 设置.auto_check_favorites == true then
 *   if 设置.last_auto_check_time < os.time() - 设置.auto_check_favorites_time * 86400 then
 *     设置.last_auto_check_time = os.time()   -- 先写时间戳，再开始查
 *     保存设置()
 *     更新订阅(true)
 *   end
 * end
 * ```
 * 三个关键细节，本实现逐一保留：
 *
 * 1. **先写时间戳再检查**。原版在发起检查**之前**就把 `last_auto_check_time`
 *    更新为当前时间。这样即使检查中途被杀/失败，也不会在下次启动时立刻重试，
 *    不会出现"每次开 App 都狂查一遍"。
 * 2. **完成后**（home_func.lua:6758）**再写一次**时间戳 —— 覆盖耗时较长的检查。
 *    两处写入并不矛盾：第一处防止崩溃后重入，第二处记录真实完成时刻。
 * 3. 检查完成时若用户**不在**收藏夹页，弹通知栏提醒：
 *    `"检查收藏夹更新完成" / "发现 N 项更新，检测失败 M 项"`。
 *    这一条是必要的 —— 启动时自动跑的检查，用户看不到进度条，
 *    必须有个"跑完了/有新东西"的出口。
 *
 * ## 为什么用 WorkManager 承载"启动时检查"
 *
 * 语义上确实只需要"App 启动时判断一次间隔"。但直接挂在 `onCreate` 上有两个问题：
 * - 启动瞬间抢占主线程/网络，拖慢冷启动；
 * - 用户启动后立刻退出，检查被中断。
 *
 * WorkManager 的 `OneTimeWorkRequest` 恰好解决这两点：异步执行、不受前台生命周期
 * 影响，且能声明"不计费网络"约束。所以这里用**一次性任务**表达一次"启动检查"，
 * 由 [FavoriteUpdateCheckScheduler] 在启动时按间隔决定是否入队 ——
 * 判断逻辑与原版完全一致，只是执行体被挪到了后台。
 *
 * 约束选 `UNMETERED`（不计费网络）：检查会拉取每个收藏文件夹的页面，是真实流量。
 * 原版没这个顾虑（它跑在用户刚主动打开 App、通常连着 WiFi 的场景），
 * 但我们既然后台化了，就该替用户把关。若当前非 WiFi，任务会等到 WiFi 再执行 ——
 * 这正是期望行为：宁可晚点查，不该偷跑用户流量。
 *
 * ## 失败策略
 *
 * 一律返回 [Result.success]。失败不应触发 WorkManager 退避重试（重试代价是再次
 * 抓取对方页面）。时间戳已经在入队前写过，下个周期自然重来。
 */
@HiltWorker
class FavoriteUpdateCheckWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val shareRepository: ShareRepository,
    private val settingsStore: SettingsStore
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val days = settingsStore.autoCheckFavoritesDays.first()
        if (days <= 0) {
            // 入队后、执行前用户关掉了开关 —— 直接退出。
            return Result.success()
        }

        val summary = shareRepository.checkFolderUpdates().getOrNull()

        // 对齐原版 home_func.lua:6758：完成后再次记录时间戳
        settingsStore.setLastAutoCheckTime(System.currentTimeMillis())

        // 对齐原版：检查完成时用户不在收藏夹页 → 弹通知。
        // 这里无法知道用户当前在哪个页面（Worker 是后台进程），
        // 所以只做"有更新才通知"，避免每次自动检查都打扰用户。
        if (summary != null && summary.updated > 0) {
            notifyDone(summary.updated, summary.failed)
        }
        return Result.success()
    }

    /** 通知栏提醒（对齐原版 ty_core.lua 的 `通知栏提醒`） */
    private fun notifyDone(updated: Int, failed: Int) {
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "收藏夹更新",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "收藏夹自动检查完成提醒" }
                )
            }
            // 文案对齐原版：发现 N 项更新，检测失败 M 项
            val text = buildString {
                append("发现 $updated 项更新")
                if (failed > 0) append("，检测失败 $failed 项")
            }
            val n = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("检查收藏夹更新完成")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(NOTIFY_ID, n)
        }
    }

    companion object {
        private const val CHANNEL_ID = "cloudbox_favorite_update"
        private const val NOTIFY_ID = 0x10B0
    }
}
