package com.kubedroid.core.network.pods

import kotlinx.coroutines.flow.Flow

/**
 * Contract for pod list/watch/delete operations.
 */
interface PodRepository {

    /**
     * Returns pods in the provided namespace.
     */
    suspend fun listPods(namespace: String): Result<PodListResult>

    /**
     * Watches pod changes for the provided namespace.
     */
    fun watchPods(namespace: String): Flow<Result<PodListResult>>

    /**
     * Deletes a pod by name in the provided namespace.
     */
    suspend fun deletePod(namespace: String, podName: String): Result<Unit>
}
