package com.cloudbox.app.core.data.announcement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 公告未读状态跨页共享（启动检测写入；网盘页顶栏信封图标读它显示红点）。
 *
 * 与 [com.cloudbox.app.core.data.update.UpdateStatusStore] 同一模式：
 * 检查发生在 MainActivity 启动链路，红点显示在 FileListScreen 顶栏。
 */
@Singleton
class AnnouncementStatusStore @Inject constructor() {

    private val _unread = MutableStateFlow(false)

    /** true = 有未读公告（红点显示条件） */
    val unread: StateFlow<Boolean> = _unread.asStateFlow()

    fun setUnread(value: Boolean) {
        _unread.value = value
    }
}
