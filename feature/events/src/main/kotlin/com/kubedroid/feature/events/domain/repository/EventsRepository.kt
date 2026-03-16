package com.kubedroid.feature.events.domain.repository

import com.kubedroid.feature.events.domain.model.ClusterEvent
import kotlinx.coroutines.flow.Flow

interface EventsRepository {
    fun watchAllEvents(namespace: String?): Flow<List<ClusterEvent>>

    suspend fun getEventsByObject(
        kind: String,
        name: String,
        namespace: String?,
    ): Result<List<ClusterEvent>>
}
