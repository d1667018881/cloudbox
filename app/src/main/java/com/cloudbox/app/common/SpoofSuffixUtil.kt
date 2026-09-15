package com.cloudbox.app.common

/**
 * 上传后缀伪装：哪些格式在上传前要改名为 `.zip`。
 *
 * ─────────────────────────────────────────────────────────────
 * 为什么这是可配置的
 * ─────────────────────────────────────────────────────────────
 * 蓝奏云**按扩展名决定能不能上传**（实测：无扩展名一律回
 * `{"zt":0,"info":"不能上传.格式的文件"}`）。被拦的格式列表由服务端定，
 * 且会变 —— 硬编码意味着每次都要改代码重新发版。
 *
 * 所以：给一份合理默认值，同时允许用户在设置页自己增删。
 *
 * 注意伪装的可逆性：上传时 `x.exe` → `x.zip`，下载时按同名 `.zip`
 * 还原回 `x.exe`（见 UploadRepositoryImpl 的还原逻辑）。
 * 所以**只加真正需要伪装的格式**，加多了反而让正常文件多绕一圈。
 */
object SpoofSuffixUtil {

    /** 默认伪装列表（对齐原版 + 实测被拦的常见可执行格式） */
    val DEFAULT_SUFFIXES = listOf("exe", "apk", "msi", "bat", "sh", "dll", "jar")

    /** 默认列表的存储形式（逗号分隔，便于 DataStore 存字符串） */
    val DEFAULT_RAW = DEFAULT_SUFFIXES.joinToString(",")

    /**
     * 解析用户配置的后缀串。
     *
     * 容错处理（用户手输的东西不可能规整）：
     * - 逗号 / 顿号 / 空格 / 换行 都当分隔符
     * - 去掉前导点（`.exe` 和 `exe` 等价）
     * - 统一小写
     * - 去重、去空
     *
     * 传空串时返回默认列表：用户清空输入框的意图通常是"没配过"，
     * 而不是"一个都不要"（真不要伪装应该去关总开关）。
     */
    fun parse(raw: String): Set<String> {
        if (raw.isBlank()) return DEFAULT_SUFFIXES.toSet()
        return raw
            .split(',', '，', '、', ' ', '\n', '\t')
            .asSequence()
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { DEFAULT_SUFFIXES.toSet() }
    }

    /** 规范化成存盘格式（逗号分隔、去重、排序） */
    fun normalize(raw: String): String = parse(raw).sorted().joinToString(",")
}
