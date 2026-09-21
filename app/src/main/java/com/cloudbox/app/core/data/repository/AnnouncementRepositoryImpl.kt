package com.cloudbox.app.core.data.repository

import com.cloudbox.app.core.data.local.datastore.SettingsStore
import com.cloudbox.app.core.data.remote.AnnouncementSource
import com.cloudbox.app.core.domain.model.Announcement
import com.cloudbox.app.core.domain.repository.AnnouncementRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnouncementRepositoryImpl @Inject constructor(
    private val source: AnnouncementSource,
    private val settingsStore: SettingsStore
) : AnnouncementRepository {

    override suspend fun fetch(): Result<List<Announcement>> = source.fetch()

    override suspend fun lastReadId(): String = settingsStore.lastReadAnnouncementId.first()

    override suspend fun markRead(id: String) = settingsStore.setLastReadAnnouncementId(id)
}
