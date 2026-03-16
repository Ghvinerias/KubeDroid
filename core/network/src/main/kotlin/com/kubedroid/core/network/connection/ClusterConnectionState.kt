package com.kubedroid.core.network.connection

/**
 * Lifecycle states for cluster connectivity.
 */
sealed interface ClusterConnectionState {
    data object Disconnected : ClusterConnectionState

    data class Connecting(
        val contextName: String,
    ) : ClusterConnectionState

    data class Connected(
        val contextName: String,
    ) : ClusterConnectionState

    data class Failed(
        val contextName: String,
        val reason: String?,
    ) : ClusterConnectionState
}
