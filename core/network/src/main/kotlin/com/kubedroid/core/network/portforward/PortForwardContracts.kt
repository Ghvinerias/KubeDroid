package com.kubedroid.core.network.portforward

import kotlinx.coroutines.flow.Flow

/**
 * Lifecycle states emitted by a port-forward session.
 */
sealed class PortForwardStatus {
    data object Starting : PortForwardStatus()
    data object Active : PortForwardStatus()
    data class Failed(val cause: Throwable) : PortForwardStatus()
    data object Closed : PortForwardStatus()
}

/**
 * Active port-forward session contract.
 */
interface PortForwardSession {
    val localPort: Int
    val statusFlow: Flow<PortForwardStatus>

    fun close()
}
