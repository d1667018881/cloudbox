package com.cloudbox.app.core.data.repository

import com.cloudbox.app.core.data.remote.LanzouApiClient
import com.cloudbox.app.core.domain.repository.ProfileRepository
import com.cloudbox.app.core.domain.repository.ProfileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号中心设置实现（task=7 / 8 / 10 / 15，对齐原版 account.lua）。
 *
 * 每个方法都走 [safe]，统一三段式：
 *   1. 前置校验 —— 服务端对非法入参返回的是 zt 非 1 且 info 为空串，**没有有效提示**，
 *      与其让用户在对话框里看到 "(null)"，不如本地先拦；
 *   2. 调接口，只有 `zt == 1` 判成功（见 [judge]）；
 *   3. 任何异常兜成 Failure，不向上抛，UI 直接可渲染。
 *
 * 未实现 task=43（修改手机号）：需要短信验证码，App 侧拿不到，
 * 把它做成必失败的功能只会误导用户。
 */
@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val apiClient: LanzouApiClient
) : ProfileRepository {

    override suspend fun setPersonalLinkCode(enableCode: Boolean, code: String): ProfileResult =
        safe {
            if (enableCode && code.isBlank()) return@safe failure("请输入访问码")
            val resp = apiClient.apiService.setPersonalLinkCode(
                codeoff = if (enableCode) 0 else 1,
                code = code
            )
            judge(resp.zt, resp.infoText)
        }

    override suspend fun changePassword(oldPwd: String, newPwd: String): ProfileResult =
        safe {
            if (oldPwd.isBlank() || newPwd.isBlank()) return@safe failure("请输入旧密码和新密码")
            if (newPwd.length < 6) return@safe failure("新密码至少 6 位")
            val resp = apiClient.apiService.changePassword(oldPwd = oldPwd, newPwd = newPwd)
            judge(resp.zt, resp.infoText, "旧密码不正确，或新密码不合法")
        }

    override suspend fun setExternalLink(title: String, summary: String): ProfileResult =
        safe {
            if (title.isBlank()) return@safe failure("标题不能为空")
            val resp = apiClient.apiService.setExternalLink(ubt = title, usm = summary)
            judge(resp.zt, resp.infoText)
        }

    override suspend fun setPublisher(show: Boolean, nickname: String): ProfileResult =
        safe {
            if (show && nickname.isBlank()) return@safe failure("请输入要展示的昵称")
            val resp = apiClient.apiService.setPublisher(
                shows = if (show) 1 else 0,
                shownames = nickname
            )
            judge(resp.zt, resp.infoText)
        }

    // ==================== 内部辅助 ====================

    private fun failure(reason: String) = ProfileResult.Failure(reason)

    /** 服务端判定：只有 zt==1 算成功 */
    private fun judge(zt: Int, info: String?, fallback: String? = null): ProfileResult =
        if (zt == 1) ProfileResult.Success(info)
        else ProfileResult.Failure(info ?: fallback ?: "设置失败（zt=$zt）")

    /** IO 线程执行 + 异常兜底。用 suspend lambda 而非 inline：
     *  inline lambda 跨 withContext 传递会被编译器以
     *  "it may contain non-local returns" 拒绝。 */
    private suspend fun safe(block: suspend () -> ProfileResult): ProfileResult =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            ProfileResult.Failure(e.message ?: "网络请求失败")
        }
}
