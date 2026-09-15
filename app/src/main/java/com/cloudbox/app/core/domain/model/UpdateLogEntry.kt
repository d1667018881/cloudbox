package com.cloudbox.app.core.domain.model

/** 更新日志条目（关于页展示用） */
data class UpdateLogEntry(
    /** 版本号，如 v0.1.130 */
    val version: String,
    /** 该版本的改动要点 */
    val lines: List<String>
)
