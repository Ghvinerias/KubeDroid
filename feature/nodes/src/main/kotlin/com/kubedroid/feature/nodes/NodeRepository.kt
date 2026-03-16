package com.kubedroid.feature.nodes

import com.kubedroid.core.database.cache.StaleDataIndicator
import com.kubedroid.core.network.nodes.Node
import com.kubedroid.core.network.nodes.NodeActionResult
import com.kubedroid.core.network.nodes.NodeDrainProgress
import kotlinx.coroutines.flow.Flow

/**
 * Feature-facing contract for node list/watch and lifecycle actions.
 */
interface NodeRepository {

    /**
     * Returns all cluster nodes.
     */
    suspend fun list(): Result<NodeListData>

    /**
     * Watches node changes.
     */
    fun watch(): Flow<Result<NodeListData>>

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

data class NodeListData(
    val items: List<Node>,
    val staleDataIndicator: StaleDataIndicator,
)
