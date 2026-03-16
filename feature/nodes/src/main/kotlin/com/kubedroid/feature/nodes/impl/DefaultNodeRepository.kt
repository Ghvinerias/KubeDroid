package com.kubedroid.feature.nodes.impl

import com.kubedroid.core.network.nodes.Node
import com.kubedroid.core.network.nodes.NodeActionResult
import com.kubedroid.core.network.nodes.NodeDrainProgress
import com.kubedroid.feature.nodes.NodeListData
import com.kubedroid.feature.nodes.NodeRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DefaultNodeRepository @Inject constructor(
    private val coreRepository: com.kubedroid.core.network.nodes.NodeRepository,
) : NodeRepository {

    override suspend fun list(): Result<NodeListData> = coreRepository.list().map { list ->
        NodeListData(
            items = list.nodes,
            staleDataIndicator = list.staleDataIndicator,
        )
    }

    override fun watch(): Flow<Result<NodeListData>> =
        coreRepository.watch().map { result ->
            result.map { list ->
                NodeListData(
                    items = list.nodes,
                    staleDataIndicator = list.staleDataIndicator,
                )
            }
        }

    override suspend fun cordon(nodeName: String): NodeActionResult = coreRepository.cordon(nodeName)

    override suspend fun uncordon(nodeName: String): NodeActionResult = coreRepository.uncordon(nodeName)

    override suspend fun drain(nodeName: String): NodeActionResult = coreRepository.drain(nodeName)

    override fun drainWithProgress(nodeName: String): Flow<NodeDrainProgress> {
        return coreRepository.drainWithProgress(nodeName)
    }
}
