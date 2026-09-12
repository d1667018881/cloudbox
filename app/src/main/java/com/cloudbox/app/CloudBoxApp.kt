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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        apiClient.bindSettings(appScope)
        appScope.launch {
            // 启动会话自愈：Cookie 过期则尝试静默重登
            authRepository.ensureSession()
            // 启动时拉取远程域名配置（未配置远程 URL 时 refreshRemote 直接返回失败，静默忽略）
            domainRepository.refreshRemote()
        }
        // 自检：WorkManager 是否真的挂上了 Hilt 工厂。
        //
        // 为什么必须显式检查：如果 Factory 没生效，任何 @HiltWorker 都无法被构造，
        // WorkManager 会在调度时抛异常并把任务记为 FAILED —— 而"上传"恰恰完全
        // 依赖 Worker。这类故障在 UI 上表现为"秒失败/秒成功、文件没上去"，
        // 且不会有任何可读错误。启动时打一行日志，排查时一眼就能排除这个方向。
        runCatching {
            Log.i(
                "CloudBoxUpload",
                "WorkManager 初始化：workerFactory=${workerFactory.javaClass.simpleName} " +
                    "isDefault=${workManagerConfiguration.workerFactory === workerFactory}"
            )
        }
    }
}
