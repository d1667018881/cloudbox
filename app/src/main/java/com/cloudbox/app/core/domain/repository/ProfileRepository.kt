package com.cloudbox.app.core.domain.repository

/**
 * 账号中心的"偏好设置"接口（原版 `account.lua`，逆向所得）。
 *
 * 仓库此前**完全没有实现**这一块，是功能对照时发现的缺口：
 * 原版能改，本 App 此前无处可改。
 *
 * 四个接口的失败一旦发生，服务端会在 `info` 里给一句中文提示，
 * success 判定统一为 `zt == 1`（与文件/文件夹操作一致）。
 *
 * ⚠️ 这些接口的参数名是蓝奏云历史遗留、极度反直觉，改动前务必对照注释：
 *    - task=10 的 `ubt`/`usm` 是外链**标题/简介**，不是 URL；
 *    - task=7 的 `codeoff` 是**反**的（0=需要访问码）；
 *    - task=15 复用了"提取码"那对字段名 `shows`/`shownames`，但语义是**显示发布者/昵称**。
 */
sealed class ProfileResult {
    data class Success(val info: String? = null) : ProfileResult()
    data class Failure(val reason: String) : ProfileResult()
}

interface ProfileRepository {

    /**
     * 个人分享链访问码 task=7。
     * @param enableCode true=需要访问码（codeoff=0）；false=关闭（codeoff=1）
     * @param code 访问码内容
     */
    suspend fun setPersonalLinkCode(enableCode: Boolean, code: String): ProfileResult

    /**
     * 修改登录密码 task=8。
     * 旧密码/新密码都以**明文**提交 —— 原版就是这样（与登录同源，服务端自己处理）。
     */
    suspend fun changePassword(oldPwd: String, newPwd: String): ProfileResult

    /**
     * 外链（个人主页）标题与简介 task=10。
     * @param title   → 字段 ubt
     * @param summary → 字段 usm
     */
    suspend fun setExternalLink(title: String, summary: String): ProfileResult

    /**
     * 是否显示发布者 task=15。
     * @param show true=显示（shows=1）
     * @param nickname 展示的发布者昵称 → 字段 shownames
     */
    suspend fun setPublisher(show: Boolean, nickname: String): ProfileResult
}
