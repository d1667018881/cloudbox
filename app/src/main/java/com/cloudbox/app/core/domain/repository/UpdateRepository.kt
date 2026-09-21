package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.AppUpdate
import java.io.File

/**
 * 应用自更新仓库。
 *
 * 语义区分（很重要）：[checkUpdate] 的 `success(null)` 表示"已是最新"，
 * `failure` 表示"检查失败"（网络不可达等）。二者不能混为一谈 ——
 * 检查失败时报"已是最新"会让用户错过更新，反之则天天误报。
 */
interface UpdateRepository {

    /** 检查更新。success(null)=已最新；success(AppUpdate)=有新版；failure=检查失败 */
    suspend fun checkUpdate(): Result<AppUpdate?>

    /**
     * 下载更新包到 App 私有目录（cacheDir/updates/），返回文件句柄。
     * @param onProgress 0..100 的进度回调（在 IO 线程调用）
     */
    suspend fun download(update: AppUpdate, onProgress: (Int) -> Unit): Result<File>
}
