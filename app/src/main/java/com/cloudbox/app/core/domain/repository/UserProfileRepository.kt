package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.UserProfile

/**
 * 账户概览仓库（原版「获取用户信息」，`myfile.php?item=1&v2`）。
 *
 * 与只管**写操作**的 [ProfileRepository]（task=7/8/10/15）分开：
 * 这里只负责读，职责单一，也避免把"读"塞进一个 KDoc 写明"四个接口"的写仓库里。
 */
interface UserProfileRepository {

    /**
     * 拉取账户概览。
     *
     * 返回 [Result.success] 时保证 `profile.valid == true`（已登录）；
     * 未登录/过期/网络失败一律 [Result.failure]，失败原因可直接展示。
     */
    suspend fun fetchProfile(): Result<UserProfile>
}
