package com.kubedroid.core.network.pods

import com.kubedroid.core.database.cache.StaleDataIndicator

/**
 * Container details exposed in pod listings.
 */
data class ContainerInfo(
    val name: String,
    val image: String,
    val ready: Boolean,
    val restartCount: Int,
)

/**
 * Lifecycle/status values for Kubernetes pods.
 */
sealed interface PodStatus {
    data object Pending : PodStatus
    data object Running : PodStatus
    data object Succeeded : PodStatus
    data object Failed : PodStatus
    data object Unknown : PodStatus
}

/**
 * Core pod model used by list/watch operations.
 */
data class Pod(
    val name: String,
    val namespace: String,
    val status: PodStatus,
    val nodeName: String?,
    val startTimeEpochMillis: Long?,
    val containers: List<ContainerInfo>,
)

data class PodListResult(
    val pods: List<Pod>,
    val staleDataIndicator: StaleDataIndicator,
)
