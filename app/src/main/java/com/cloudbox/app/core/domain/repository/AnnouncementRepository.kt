package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.Announcement

/** 公告仓库：拉取 + 已读状态（已读 id 存 DataStore） */
interface AnnouncementRepository {

    /** 拉取公告列表；失败返回 failure（UI 降级为"暂无公告"，不遮挡页面） */
    suspend fun fetch(): Result<List<Announcement>>

    /** 最近一次已读的公告 id（空串 = 从未读过） */
    suspend fun lastReadId(): String

    /** 标记已读到某条（打开公告页时写入最新一条的 id，用于清除红点） */
    suspend fun markRead(id: String)
}
