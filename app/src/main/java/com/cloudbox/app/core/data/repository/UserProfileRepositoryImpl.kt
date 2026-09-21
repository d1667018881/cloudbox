package com.cloudbox.app.core.data.repository

import com.cloudbox.app.core.data.remote.LanzouApiClient
import com.cloudbox.app.core.data.remote.UserProfileHtmlParser
import com.cloudbox.app.core.domain.model.UserProfile
import com.cloudbox.app.core.domain.repository.UserProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账户概览实现：走共享 [LanzouApiClient]（自动带 Cookie/UA/域名重写）。
 *
 * 响应是 HTML，不是 JSON —— 不能用 Gson 转换器，所以接口声明为
 * `Response<ResponseBody>`，在这里取字符串交给 [UserProfileHtmlParser]。
 */
@Singleton
class UserProfileRepositoryImpl @Inject constructor(
    private val apiClient: LanzouApiClient
) : UserProfileRepository {

    override suspend fun fetchProfile(): Result<UserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = apiClient.apiService.getUserProfile()
            if (!resp.isSuccessful) error("账户概览请求失败（HTTP ${resp.code()}）")
            val html = resp.body()?.string().orEmpty()
            if (html.isBlank()) error("账户概览响应为空")
            val profile = UserProfileHtmlParser.parse(html)
            if (!profile.valid) error("未登录或登录已过期，请重新登录")
            profile
        }
    }
}
