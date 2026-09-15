package com.cloudbox.app.core.domain.model

/** 网盘条目：文件夹或文件（统一模型，UI 列表用） */
data class CloudFile(
    val id: Long,
    val name: String,
    val isFolder: Boolean,
    val size: String?,   // 原样字符串（"12.3 MB"），蓝奏云无字节数值
    val time: String?,
    val onof: String?,   // 是否设提取码
    val isDes: String?,  // 是否有描述
    val parentId: Long
) {
    /**
     * 文件类型标签（对齐原版 `列表数据[i].fileType`，home_func.lua:5408）。
     *
     * 原版的推导规则逐条还原：
     * ```lua
     * if _t1:find("%.") then                        -- ① 必须含点号
     *   if 后缀 ~= "" then                          -- ② 后缀非空
     *     if tonumber(后缀) == nil then             -- ③ 后缀不是纯数字
     *       if not 后缀:find("[\xe4-\xe9][\x80-\xbf][\x80-\xbf]") then
     *                                               -- ④ 后缀不含中文（UTF-8 三字节）
     *         if 后缀:find("%w") then               -- ⑤ 后缀含字母或数字
     *           if #后缀 < 10 then                  -- ⑥ 后缀短于 10 字符
     *             fileType = lower(后缀)
     * ```
     * 这几条合起来就是一句人话：**取最后一个点之后的、看起来像扩展名的那一段**。
     * 加 ④ 是因为有些文件名本身带中文（如"报告.最终版"），
     * 那种情况下"最终版"是标题的一部分，不该被当成类型标签。
     * 加 ⑥ 是防止把"文件.2024年备份存档"这种误判成扩展名。
     *
     * 返回 null 表示没有可用标签（原版此时整个标签卡片隐藏）。
     */
    val fileType: String? get() {
        if (isFolder) return null                    // 原版对文件夹不显示类型
        val dot = name.lastIndexOf('.')
        // ① 没点号 / 点在开头或结尾（如 ".gitignore"、"结尾."）都不算
        if (dot <= 0 || dot == name.length - 1) return null
        val ext = name.substring(dot + 1)
        if (ext.isEmpty()) return null               // ②
        if (ext.all { it.isDigit() }) return null    // ③ 纯数字不是扩展名
        // ④ 含 CJK 字符 → 是标题的一部分，不是扩展名
        if (ext.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }) return null
        if (ext.none { it.isLetterOrDigit() }) return null  // ⑤
        if (ext.length >= 10) return null            // ⑥
        return ext.lowercase()
    }
}

/** 一页文件列表结果 */
data class FileListPage(
    val folders: List<CloudFile>,
    val files: List<CloudFile>,
    val hasMore: Boolean
)
