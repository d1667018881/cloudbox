package com.cloudbox.app.core.domain.model

/**
 * 账户概览（原版「获取用户信息」，`myfile.php?item=1&v2` 的 HTML 解析结果）。
 *
 * 对齐原版 `home_func.lua` 的 `获取用户信息`（:2035 起）：它请求账户概览页，
 * 用一串 Lua 模式从 HTML 里抓字段，回调带 11 个位置参数。这里把它规整成
 * 具名字段，避免移植时"第 9 个参数到底是下载数还是文件数"这类易错点。
 *
 * ⚠️ 原版 item=1 响应**不含**会员等级/到期时间/空间容量/手机号 ——
 * 这些在原版里要么不展示、要么另开网页，故此处不臆造字段。
 *
 * @param userName           登录用户名（原版回调参数 1）
 * @param displayName        页面昵称（参数 2）
 * @param shareLink          个人分享链 URL（参数 3）
 * @param shareLinkCode      个人分享链提取码（参数 4）
 * @param externalLinkTitle  外链（个人主页）标题 ubt（参数 5）
 * @param externalLinkSummary 外链简介 usm（参数 6）
 * @param publisherVisible   是否显示发布者（参数 7，由页面标记反推）
 * @param publisherName      发布者昵称 shownames（参数 8/11）
 * @param downloadCount      累计下载数（参数 9；原版注明是会员个性化统计，非会员可能为空）
 * @param fileCount          累计文件数（参数 10；删除后不减）
 */
data class UserProfile(
    val userName: String? = null,
    val displayName: String? = null,
    val shareLink: String? = null,
    val shareLinkCode: String? = null,
    val externalLinkTitle: String? = null,
    val externalLinkSummary: String? = null,
    val publisherVisible: Boolean = false,
    val publisherName: String? = null,
    val downloadCount: String? = null,
    val fileCount: String? = null
) {
    /**
     * 是否解析命中。
     *
     * 未登录/登录过期时服务端返回的是登录页 HTML，所有字段都落空；
     * 用"用户名与昵称同时为空"判定为未命中（而非"账号信息为空"）。
     */
    val valid: Boolean get() = !userName.isNullOrBlank() || !displayName.isNullOrBlank()
}
