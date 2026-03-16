package com.kubedroid.core.network.connection

import com.kubedroid.core.network.kubeconfig.KubeContext
import kotlinx.coroutines.flow.Flow

/**
 * Contract for opening/closing cluster connections and exposing connection state updates.
 */
interface ClusterConnectionRepository {

    /**
     * Connects to the given Kubernetes context.
     */
    suspend fun connect(context: KubeContext): Result<Unit>

    /**
     * Disconnects from the active cluster connection.
     */
    suspend fun disconnect(): Result<Unit>

    /**
     * Emits the current and subsequent cluster connection states.
     */
    fun getState(): Flow<ClusterConnectionState>
}
