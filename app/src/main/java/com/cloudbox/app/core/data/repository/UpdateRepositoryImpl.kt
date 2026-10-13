package com.cloudbox.app.core.data.repository

import android.content.Context
import com.cloudbox.app.BuildConfig
import com.cloudbox.app.core.domain.model.AppUpdate
import com.cloudbox.app.core.domain.repository.UpdateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自更新实现：GitHub Releases API + APK 流式下载。
 *
 * 为什么复用共享 [OkHttpClient]：它的域名重写拦截器只对占位 host 生效，
 * 对本处的 `api.github.com` / `objects.githubusercontent.com` 无影响；
 * 而 UA 补全与重试策略对 GitHub 同样有益。
 *
 * APK 下到 `cacheDir/updates/`（App 私有目录）而非公共 `Download/`：
 * targetSdk 34 下 App 够不着公共目录，且安装要走 FileProvider，
 * 私有目录 + `file_paths.xml` 是唯一干净路径。
 */
@Singleton
class UpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) : UpdateRepository {

    override suspend fun checkUpdate(): Result<AppUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("检查更新失败（HTTP ${resp.code}）")
                val json = JSONObject(resp.body?.string().orEmpty())
                val tag = json.optString("tag_name", "")
                if (tag.isBlank()) return@runCatching null

                // 版本比较（V59 三段式，2026-10-13 版本号规约切换 0.1.x → 1.0.0）：
                // 旧逻辑拿 tag 末段 run_number 整数比——v1.0.0 末段是 0，红点链会断。
                // 新逻辑：tag 与本地 VERSION_NAME 各解析 (major, minor, patch) 逐段比，
                // 兼容两代 tag（v0.1.231 / v1.0.0），major 不同也能比（1.0.0 > 0.1.231）。
                val remote = parseVersionTriple(tag) ?: return@runCatching null
                val local = parseVersionTriple(BuildConfig.VERSION_NAME)
                if (local != null && remote <= local) return@runCatching null
                val remoteCode = remote.first * 10000 + remote.second * 100 + remote.third

                val downloadUrl = json.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                        ?.optString("browser_download_url")
                }.orEmpty()
                if (downloadUrl.isBlank()) return@runCatching null

                AppUpdate(
                    versionCode = remoteCode,
                    versionName = tag.removePrefix("v"),
                    releaseNotes = json.optString("body", ""),
                    downloadUrl = downloadUrl,
                    publishedAt = json.optString("published_at", "").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    /** 解析 `v1.0.0` / `1.0.0` / `v0.1.231` 形态为三段整数；不合法返回 null。
     *  （V59 教训：类里已有 companion object 常量区——本函数不是常量，
     *   挂对象还差点造成「Only one companion object」；普通 private 函数即可） */
    private fun parseVersionTriple(v: String): Triple<Int, Int, Int>? = runCatching {
        val m = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(v.trim()) ?: return@runCatching null
        val (maj, min, pat) = m.destructured
        Triple(maj.toInt(), min.toInt(), pat.toInt())
    }.getOrNull()

    override suspend fun download(update: AppUpdate, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "cloudbox-${update.versionName}.apk")
                if (file.exists()) file.delete()

                val request = Request.Builder().url(update.downloadUrl).build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("下载失败（HTTP ${resp.code}）")
                    val body = resp.body ?: error("下载响应为空")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var sum = 0L
                            var lastPct = -1
                            var read = input.read(buf)
                            while (read >= 0) {
                                output.write(buf, 0, read)
                                sum += read
                                if (total > 0) {
                                    val pct = (sum * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress(pct)
                                    }
                                }
                                read = input.read(buf)
                            }
                        }
                    }
                }
                file
            }
        }

    companion object {
        private const val RELEASES_LATEST_URL =
            "https://api.github.com/repos/d1667018881/cloudbox/releases/latest"
    }
}
