package com.cloudbox.app.core.data.update

import com.cloudbox.app.core.domain.model.AppUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 更新状态跨页共享。
 *
 * 为什么需要它：启动时的后台检查发生在 `MainActivity`，而红点显示在
 * `MainScreen` 的"关于"入口 —— 两处不同的组合树，用 [StateFlow] 共享最直接，
 * 也避免把"是否可更新"塞进每个 ViewModel。
 *
 * 对齐原版：原版主页侧栏"关于"入口有 `关于更新红点`（home.lua:8977-8979）。
 */
@Singleton
class UpdateStatusStore @Inject constructor() {

    private val _available = MutableStateFlow<AppUpdate?>(null)

    /** 非 null = 有新版本（红点显示条件） */
    val available: StateFlow<AppUpdate?> = _available.asStateFlow()

    fun set(update: AppUpdate?) {
        _available.value = update
    }

    fun clear() {
        _available.value = null
    }
}
