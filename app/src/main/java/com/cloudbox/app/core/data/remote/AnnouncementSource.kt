package com.cloudbox.app.core.data.remote

import com.cloudbox.app.core.domain.model.Announcement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 公告远程源：从仓库内的 `announcements.json` 拉取（见仓库根同名文件）。
 *
 * JSON 格式：
 * {
 *   "version": 1,
 *   "announcements": [
 *     { "id": "2026-09-001", "title": "…", "body": "…", "date": "2026-09-21", "pinned": true }
 *   ]
 * }
 *
 * 用双 URL 兜底（jsDelivr CDN 优先，raw.githubusercontent 备选），任一成功即返回；
 * 全失败才报错。解析用 org.json（Android 自带），不为一次拉取引依赖 ——
 * 与 [RemoteDomainSource] 同一套路。
 */
@Singleton
class AnnouncementSource @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun fetch(): Result<List<Announcement>> = withContext(Dispatchers.IO) {
        runCatching {
            var lastError: Throwable? = null
            for (url in URLS) {
                val result = runCatching { fetchFrom(url) }
                if (result.isSuccess) return@runCatching result.getOrThrow()
                lastError = result.exceptionOrNull()
            }
            throw lastError ?: IllegalStateException("公告源不可达")
        }
    }

    private fun fetchFrom(url: String): List<Announcement> {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr: JSONArray = json.optJSONArray("announcements") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").trim()
                if (title.isBlank()) return@mapNotNull null
                val id = o.optString("id").trim().ifBlank { title }
                Announcement(
                    id = id,
                    title = title,
                    body = o.optString("body").trim(),
                    date = o.optString("date").trim().takeIf { it.isNotBlank() },
                    pinned = o.optBoolean("pinned", false)
                )
            }
        }
    }

    companion object {
        private val URLS = listOf(
            "https://cdn.jsdelivr.net/gh/d1667018881/cloudbox@main/announcements.json",
            "https://raw.githubusercontent.com/d1667018881/cloudbox/main/announcements.json"
        )
    }
}
