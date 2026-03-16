package com.kubedroid.feature.pods.portforward

import com.kubedroid.core.network.portforward.PortForwardSession

/**
 * Parameters required to open a pod port-forward session.
 */
data class PodPortForwardRequest(
    val apiServer: String,
    val namespace: String,
    val podName: String,
    val remotePort: Int,
    val localPort: Int? = null,
    val bearerToken: String? = null,
)

/**
 * Contract for creating and managing pod port-forward sessions.
 */
interface PortForwardRepository {
    suspend fun createSession(request: PodPortForwardRequest): Result<PortForwardSession>
}
