package com.cloudbox.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cloudbox.app.core.data.remote.LanzouApiClient
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.core.domain.repository.DomainRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用入口。
 *
 * 启动时执行（均不阻塞 UI）：
 * 1. 绑定设置流 → 热更新 UA 到拦截器
 * 2. 会话检测：Cookie >18 天未活跃 → 静默重登（见 AuthRepositoryImpl.ensureSession）
 * 3. 拉取远程域名配置（#9 修复：需求规格"启动时尝试拉取"；失败静默回落本地值）
 * 4. 提供 Hilt WorkerFactory：UploadWorker 依赖注入（WorkManager 要求）
 */
@HiltAndroidApp
class CloudBoxApp : Application(), Configuration.Provider {

    @Inject lateinit var apiClient: LanzouApiClient
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var domainRepository: DomainRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ⚠️ 2026-09-12 关键修复：`get()` → `by lazy`。
     *
     * 症状（用户实测时间线，三次上传全部如此）：
     * ```
     * 批次 xxxx → ENQUEUED
     * 批次 xxxx → FAILED   runAttemptCount=0  outputData={}  ← 空
     * ```
     * Worker 从 ENQUEUED **直接跳到 FAILED**，中间没有 RUNNING，
     * 重试计数为 0，outputData 里什么都没有 —— 即 Queue 调度器压根没能
     * **构造出** UploadWorker。日志里 Worker 自己的 `Worker 启动` 一行也没出现。
     *
     * 根因：这两个属性都是 `@Inject lateinit var`，而 `workManagerConfiguration`
     * 原来写成属性 getter（`get() = ...`）。WorkManager 通过 AndroidX Startup 的
     * InitializationProvider 初始化，**时机可能早于 Application.onCreate()**；
     * 一旦在注入完成前访问，`workerFactory` 还是未初始化的 lateinit，
     * 直接抛 UninitializedPropertyAccessException，整个 WorkManager 初始化失败，
     * 于是所有 Worker 一律瞬间 FAILED，且不给任何可读错误。
     *
     * 改成 `by lazy`：把注入推迟到**第一次真正读取**该属性时。此时
     * Hilt 注入一定已经完成（读取发生在 WorkManager 用到它的时候），
     * 既保证拿到非空 factory，也不会重复构建 Configuration。
     */
    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        // ⚠️ 必须在这里**主动**初始化 WorkManager（而不是等第一次 getInstance）。
        //
        // 这是本轮"上传秒失败"的根治点：
        // manifest 按 Hilt 官方要求用 `tools:node="remove"` 摘掉了
        // WorkManagerInitializer，改为依赖 Configuration.Provider 做按需初始化。
        // 但"按需"的时机由**第一次 WorkManager.getInstance() 的调用点**决定 ——
        // 而 UploadViewModel 是通过 Hilt 注入 WorkManager 的，注入发生在
        // 任意一个 ViewModel 被创建时（可能很早，甚至在 Application 注入链完成前
        // 的边界情况下）。一旦首次初始化赶在 workerFactory 注入完成之前，
        // 读到的就是未初始化的 lateinit → 初始化失败 → 之后所有 Worker
        // 一律瞬间 FAILED（runAttemptCount=0、outputData 为空、无任何日志）。
        //
        // 在 onCreate 里主动 getInstance() 一次：此刻 Hilt 对 Application 的
        // 注入**一定**已经完成（@HiltAndroidApp 的注入在 super.onCreate 之前），
        // 配置必然正确，后续所有 getInstance() 复用这个已初始化的单例。
        runCatching { androidx.work.WorkManager.getInstance(this) }
            .onFailure {
                Log.e("CloudBoxUpload",
                    "WorkManager 主动初始化失败：${it.javaClass.simpleName} ${it.message}")
            }

        apiClient.bindSettings(appScope)
        appScope.launch {
            // 启动会话自愈：Cookie 过期则尝试静默重登
            authRepository.ensureSession()
            // 启动时拉取远程域名配置（未配置远程 URL 时 refreshRemote 直接返回失败，静默忽略）
            domainRepository.refreshRemote()
        }
        // 自检日志：确认 Hilt 工厂已注入、且配置能被读取。
        //
        // 这一步在 onCreate 里执行，注入必然已完成 —— 所以只要这行能打出来，
        // 就说明 `workerFactory` 本身没问题。真正要防的是**读取时机早于注入**
        // （见 workManagerConfiguration 注释）。这条日志用于确认修复生效。
        runCatching {
            Log.i(
                "CloudBoxUpload",
                "WorkManager 自检：factory=${workerFactory.javaClass.simpleName} " +
                    "配置可读=${workManagerConfiguration.workerFactory === workerFactory}"
            )
        }.onFailure {
            // 走到这里通常意味着读 Configuration 时注入还没完成（旧实现的病灶）。
            Log.e("CloudBoxUpload", "⚠️ WorkManager 配置读取失败：${it.javaClass.simpleName} ${it.message}")
        }
    }
}
