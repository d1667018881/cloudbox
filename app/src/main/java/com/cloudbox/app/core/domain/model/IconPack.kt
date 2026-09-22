package com.cloudbox.app.core.domain.model

/**
 * 图标包（原版 customize_settings.lua 的图标包列表项）。
 *
 * 原版约定（customize_settings.lua:2196-2220 的内置「制作规范」）：
 * - zip 根目录必须有 `info.json`：`{"name","ver","author"}`
 * - `file.png` 为未知格式兜底；其余按「后缀名小写.png」
 * - `folder/folder.png` 为文件夹图标；`other/` 下是界面小图标
 *
 * @param name   展示名（info.json.name）
 * @param version 版本（info.json.ver）
 * @param author  作者
 * @param path    解压后的包目录绝对路径（`getExternalFilesDir/icon_pack/<名>/`）
 */
data class IconPack(
    val name: String,
    val version: String,
    val author: String,
    val path: String
) {
    val displayVersion: String get() = "v$version"
}
