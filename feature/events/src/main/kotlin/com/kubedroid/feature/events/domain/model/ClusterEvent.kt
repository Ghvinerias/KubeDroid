package com.kubedroid.feature.events.domain.model

import java.time.Instant

data class ClusterEvent(
    val uid: String,
    val namespace: String,
    val involvedObjectName: String,
    val involvedObjectKind: String,
    val reason: String,
    val message: String,
    val type: ClusterEventType,
    val count: Int,
    val lastTimestamp: Instant,
)

enum class ClusterEventType {
    Normal,
    Warning,
}
