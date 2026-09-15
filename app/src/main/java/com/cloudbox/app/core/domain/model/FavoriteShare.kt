package com.cloudbox.app.core.domain.model

/**
 * 收藏的分享链接（UI 模型）。
 *
 * V30（对齐原版 v1.3.4.9）：新增 [kind] / [pass] / [hasUpdate] / [lastCheckAt] 四个字段，
 * 支撑原版新增的「检查收藏文件夹更新」功能（原版 favorites.lua:1188 的 checkingUpdate
 * 全局标志 + 列表红点，见 home_func.lua proto[35] 的 应用解析链接订阅）。
 *
 * ⚠️ 原版只对 **文件夹** 型收藏做更新检查（`设置.open_link_history[i].type == "folder"`），
 *    单文件收藏没有「更新」概念 —— 所以 [hasUpdate] 只对文件夹有意义。
 */
data class FavoriteShare(
    val shareUrl: String,
    val name: String,
    val remark: String = "",
    val createdAt: Long = 0L,
    /** 是否置顶（置顶项永远排在列表最前） */
    val pinned: Boolean = false,

    // ==================== V30 新增（对齐 v1.3.4.9） ====================

    /**
     * 收藏类型："folder" 或 "file"。
     *
     * 原版用 `open_link_history[i].type` 区分，只给 folder 项显示「更新订阅」按钮
     * （favorites.lua:1126）。这里沿用同样的字符串取值，便于与原版行为逐条对照。
     */
    val kind: String = "file",

    /** 提取码（原版 `open_link_history[i].pass`）；检查更新时要带上，否则拿到的是「输入密码」页 */
    val pass: String = "",

    /**
     * 上次检查后是否发现更新（原版 `open_link_history[i].update`）。
     * true 时列表项显示红点（原版 favorites.lua:562 更新订阅按钮 + 红点）。
     */
    val hasUpdate: Boolean = false,

    /** 上次检查时间戳（毫秒）。0 = 从未检查 */
    val lastCheckAt: Long = 0L
) {
    val isFolder: Boolean get() = kind == "folder"
}
