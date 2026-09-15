package com.cloudbox.app.feature.favorites

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cloudbox.app.core.data.local.datastore.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * 收藏夹自动检查的调度入口（V30）。
 *
 * ## 忠实还原原版的判定逻辑
 *
 * 原版 home.lua:443 是在首页初始化时同步判断：
 * ```lua
 * if 设置.auto_check_favorites == true
 *    and 设置.last_auto_check_time < os.time() - 设置.auto_check_favorites_time * 86400 then
 *     设置.last_auto_check_time = os.time()   -- 先写时间戳
 *     保存设置()
 *     更新订阅(true)                          -- 再发起检查
 * end
 * ```
 * [maybeScheduleOnStartup] 逐字复刻这个判断，差别只在于：
 * **把"发起检查"从同步调用换成入队一个后台任务**。
 * 时间戳依然在入队**之前**写入 —— 这一点必须保留，否则
 * 任务执行失败/被杀时，下次启动会立刻重试，变成"每开一次 App 就狂查一遍"。
 *
 * ## 为什么不是 PeriodicWorkRequest
 *
 * 曾经用周期任务实现，后来改回一次性 + 启动判定。原因是周期任务**偏离了原版语义**：
 * - 原版是"启动时看间隔够不够"，用户能通过"开 App"主动影响检查时机；
 *   周期任务则完全由系统掌控，用户开着 App 也看不到检查发生。
 * - 周期任务在 App 长期不用时仍会唤醒（`PeriodicWorkRequest` 最短 15 分钟起，
 *   但天级间隔仍会被系统择机触发），对"别人网盘"这种第三方资源，
 *   在用户毫无感知的情况下持续抓取并不合适。
 * - 我们提供的档位（1/3/7/14/30 天）本来就是"低频、可容忍延迟"的场景，
 *   "下次打开 App 时补上"完全够用。
 *
 * 结论：直接用一次性任务承载"本次启动该检查了"这一事件，语义最贴合，
 * 行为最容易向用户解释。
 */
object FavoriteUpdateCheckScheduler {

    private const val WORK_NAME = "cloudbox_favorite_update_check"
    private const val TAG = "CloudBoxFavCheck"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * 启动时调用：按原版规则决定是否发起一次检查。
     *
     * 对应原版 home.lua:443 的那段 `if`。这个函数**有副作用**
     * （满足条件时会写 `last_auto_check_time` 并入队任务），
     * 所以每次启动只应调用一次。
     *
     * @param days 间隔天数；<= 0 表示用户关闭了自动检查
     * @param lastCheckMillis 上次检查时间（0 = 从未检查）
     * @param nowMillis 当前时间，便于测试注入
     * @return 是否发起了检查
     */
    suspend fun maybeScheduleOnStartup(
        context: Context,
        settingsStore: SettingsStore,
        days: Int,
        lastCheckMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (days <= 0) return false

        // 原版首次使用时 last_auto_check_time 是 nil/false，比较恒为 false
        // （`nil < number` 在 Lua 里是错误，被 pcall 吞掉 → 不检查）。
        // 这里用 0 表示"从未检查"：**首次启动不发检查**，先让它成为基线。
        // 好处是用户第一次打开 App 不会被一堆"发现 N 项更新"糊一脸。
        if (lastCheckMillis <= 0L) {
            settingsStore.setLastAutoCheckTime(nowMillis)
            return false
        }

        val elapsed = nowMillis - lastCheckMillis
        if (elapsed < days * DAY_MS) return false

        // ⚠️ 顺序不能动：先写时间戳，再入队。
        // 反过来的话，任务还没跑完就崩溃/被系统清掉，下次启动会再次满足条件。
        settingsStore.setLastAutoCheckTime(nowMillis)

        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull()
        if (wm == null) {
            Log.e(TAG, "WorkManager 不可用，跳过自动检查")
            return false
        }
        val request = OneTimeWorkRequestBuilder<FavoriteUpdateCheckWorker>()
            // 只在计费网络之外跑：检查会拉取每个收藏文件夹的页面，是真实流量。
            // 当前若非 WiFi，任务会一直等到 WiFi —— 这是期望行为：
            // 宁可晚点查，也不该偷跑用户的移动流量。
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()
        runCatching {
            // KEEP：若上一次的检查还在排队/执行中，不要重复入队
            wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }.onFailure {
            Log.e(TAG, "入队自动检查失败：${it.javaClass.simpleName} ${it.message}")
            return false
        }
        Log.i(TAG, "已发起收藏夹自动检查（距上次 ${elapsed / DAY_MS} 天，阈值 $days 天）")
        return true
    }

    /** 用户关闭开关时取消排队中的检查（对应原版把开关关掉的即时效果） */
    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }

    /** 便捷重载：自行读取设置（供 CloudBoxApp 调用） */
    suspend fun maybeScheduleOnStartup(context: Context, settingsStore: SettingsStore): Boolean {
        val days = settingsStore.autoCheckFavoritesDays.first()
        val last = settingsStore.lastAutoCheckTime.first()
        return maybeScheduleOnStartup(context, settingsStore, days, last)
    }
}
