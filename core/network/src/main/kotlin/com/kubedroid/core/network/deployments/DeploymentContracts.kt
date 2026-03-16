package com.kubedroid.core.network.deployments

import com.kubedroid.core.database.cache.StaleDataIndicator

/**
 * Core deployment model used by list/watch operations.
 */
data class Deployment(
    val name: String,
    val namespace: String,
    val desiredReplicas: Int,
    val readyReplicas: Int,
    val updatedReplicas: Int,
    val availableReplicas: Int,
    val observedGeneration: Long?,
)

data class DeploymentListResult(
    val deployments: List<Deployment>,
    val staleDataIndicator: StaleDataIndicator,
)

/**
 * One rollout revision entry returned from deployment history.
 */
data class RolloutRevision(
    val revision: Long,
    val changeCause: String?,
    val createdAtEpochMillis: Long?,
)

/**
 * Result states for a deployment scale request.
 */
sealed class ScaleResult {
    data class Success(
        val namespace: String,
        val deploymentName: String,
        val previousReplicas: Int,
        val requestedReplicas: Int,
        val currentReplicas: Int,
    ) : ScaleResult()

    data object InvalidReplicaCount : ScaleResult()
    data object NotFound : ScaleResult()
    data object Forbidden : ScaleResult()
    data object Conflict : ScaleResult()
    data class Failure(val cause: Throwable) : ScaleResult()
}

/**
 * Result states for rollout lifecycle operations.
 */
sealed class RolloutResult {
    data class Success(
        val namespace: String,
        val deploymentName: String,
        val action: Action,
        val targetRevision: Long? = null,
    ) : RolloutResult()

    enum class Action {
        ROLLBACK,
        PAUSE,
        RESUME,
    }

    data object NotFound : RolloutResult()
    data object Forbidden : RolloutResult()
    data object Conflict : RolloutResult()
    data class Failure(val cause: Throwable) : RolloutResult()
}
