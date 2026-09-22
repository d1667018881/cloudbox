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

    /**
     * 下载端还原：把上传时被伪装成 `.zip` 的文件名还原回原名。
     *
     * 上传侧规则是 `原名 + ".zip"`（见 UploadRepositoryImpl；apk 还会先插版本号，
     * 于是 `Foo.apk` → `Foo-v0.1.157.apk.zip`），所以反向只需两步：
     * 去掉末尾 `.zip`，再看剩下部分是否以「需要伪装的扩展名」结尾。
     *
     * ⚠️ 为什么必须带后缀白名单校验，而不是见 `.zip` 就剥：
     * 用户上传的**真压缩包** `photos.zip` 去掉后缀只剩 `photos`（没有扩展名）
     * → 不会命中；`archive.zip.zip` 去掉后是 `archive.zip`，扩展名 `zip` 不在
     * 默认伪装列表里 → 同样保持原样。只有 `xxx.apk.zip` 这种「还原后确实是
     * 一个会被服务端拦截的格式」才动，避免把正常文件名改坏。
     *
     * @param enabledSuffixes 已规范化的伪装后缀集合（见 [parse]）
     */
    fun restoreSpoofedName(fileName: String, enabledSuffixes: Set<String>): String {
        if (!fileName.endsWith(".zip", ignoreCase = true)) return fileName
        val stem = fileName.substring(0, fileName.length - 4)
        val dot = stem.lastIndexOf('.')
        // dot <= 0：没有扩展名（`photos.zip`）或以点开头（`.apk.zip`）——都不是伪装产物
        if (dot <= 0 || dot == stem.length - 1) return fileName
        val ext = stem.substring(dot + 1).lowercase()
        return if (ext in enabledSuffixes) stem else fileName
    }
}
