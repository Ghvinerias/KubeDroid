package com.kubedroid.core.network.resources

/**
 * Simplified Kubernetes event associated with a resource.
 */
data class KubeEvent(
    val reason: String?,
    val type: String?,
    val message: String,
    val source: String?,
    val count: Int?,
    val firstTimestampEpochMillis: Long?,
    val lastTimestampEpochMillis: Long?,
)
