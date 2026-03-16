package com.kubedroid.core.network.nodes

import com.kubedroid.core.database.cache.StaleDataIndicator

/**
 * One node condition returned by Kubernetes.
 */
data class NodeCondition(
    val type: String,
    val status: String,
    val reason: String?,
    val message: String?,
    val lastTransitionTimeEpochMillis: Long?,
)

/**
 * Metrics for a single node.
 */
data class NodeMetrics(
    val cpuUsageMillicores: Long?,
    val memoryUsageBytes: Long?,
    val cpuUsagePercent: Float?,
    val memoryUsagePercent: Float?,
)

/**
 * Core node model used by list/watch operations.
 */
data class Node(
    val name: String,
    val roles: List<String>,
    val kubeletVersion: String?,
    val internalIp: String?,
    val externalIp: String?,
    val ready: Boolean,
    val unschedulable: Boolean,
    val conditions: List<NodeCondition>,
    val metrics: NodeMetrics? = null,
)

data class NodeListResult(
    val nodes: List<Node>,
    val staleDataIndicator: StaleDataIndicator,
)

/**
 * Incremental progress updates while draining a node.
 */
sealed class NodeDrainProgress {
    data class Started(
        val nodeName: String,
        val totalPods: Int,
    ) : NodeDrainProgress()

    data class EvictionAttempt(
        val nodeName: String,
        val namespace: String,
        val podName: String,
        val attempt: Int,
    ) : NodeDrainProgress()

    data class WaitingOnDisruptionBudget(
        val nodeName: String,
        val namespace: String,
        val podName: String,
        val attempt: Int,
        val retryDelayMillis: Long,
    ) : NodeDrainProgress()

    data class PodEvicted(
        val nodeName: String,
        val namespace: String,
        val podName: String,
        val evictedPods: Int,
        val totalPods: Int,
    ) : NodeDrainProgress()

    data class Completed(
        val result: NodeActionResult,
    ) : NodeDrainProgress()
}

/**
 * Result states for node lifecycle actions.
 */
sealed class NodeActionResult {
    data class Success(
        val nodeName: String,
        val action: Action,
    ) : NodeActionResult()

    enum class Action {
        CORDON,
        UNCORDON,
        DRAIN,
    }

    data object NotFound : NodeActionResult()
    data object Forbidden : NodeActionResult()
    data object Conflict : NodeActionResult()
    data class Failure(val cause: Throwable) : NodeActionResult()
}
