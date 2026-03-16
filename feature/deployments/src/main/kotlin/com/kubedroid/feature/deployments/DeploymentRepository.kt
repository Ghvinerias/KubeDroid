package com.kubedroid.feature.deployments

import com.kubedroid.core.database.cache.StaleDataIndicator
import com.kubedroid.core.network.deployments.Deployment
import com.kubedroid.core.network.deployments.RolloutResult
import com.kubedroid.core.network.deployments.RolloutRevision
import com.kubedroid.core.network.deployments.ScaleResult
import kotlinx.coroutines.flow.Flow

/**
 * Feature-facing contract for deployment list/watch and rollout actions.
 */
interface DeploymentRepository {

    /**
     * Returns deployments in the provided namespace.
     */
    suspend fun list(namespace: String): Result<DeploymentListData>

    /**
     * Watches deployment changes for the provided namespace.
     */
    fun watch(namespace: String): Flow<Result<DeploymentListData>>

    /**
     * Scales a deployment to the requested replica count.
     */
    suspend fun scale(
        namespace: String,
        deploymentName: String,
        replicas: Int,
    ): ScaleResult

    /**
     * Returns rollout history for a deployment.
     */
    suspend fun history(
        namespace: String,
        deploymentName: String,
    ): Result<List<RolloutRevision>>

    /**
     * Rolls back a deployment to a specific revision.
     * If [toRevision] is null, implementation should use the previous revision.
     */
    suspend fun rollback(
        namespace: String,
        deploymentName: String,
        toRevision: Long? = null,
    ): RolloutResult

    /**
     * Pauses rollout for the deployment.
     */
    suspend fun pause(
        namespace: String,
        deploymentName: String,
    ): RolloutResult

    /**
     * Resumes rollout for the deployment.
     */
    suspend fun resume(
        namespace: String,
        deploymentName: String,
    ): RolloutResult
}

data class DeploymentListData(
    val items: List<Deployment>,
    val staleDataIndicator: StaleDataIndicator,
)
