package com.kubedroid.core.network.nodes

import kotlinx.coroutines.flow.Flow

/**
 * Contract for node list/watch and lifecycle actions.
 */
interface NodeRepository {

    /**
     * Returns all cluster nodes.
     */
    suspend fun list(): Result<NodeListResult>

    /**
     * Watches node changes.
     */
    fun watch(): Flow<Result<NodeListResult>>

    /**
     * Marks the node as unschedulable.
     */
    suspend fun cordon(nodeName: String): NodeActionResult

    /**
     * Marks the node as schedulable.
     */
    suspend fun uncordon(nodeName: String): NodeActionResult

    /**
     * Drains all drainable workloads from the node.
     */
    suspend fun drain(nodeName: String): NodeActionResult

    /**
     * Drains node workloads while emitting incremental progress updates.
     */
    fun drainWithProgress(nodeName: String): Flow<NodeDrainProgress>
}
