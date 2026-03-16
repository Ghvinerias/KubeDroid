package com.kubedroid.feature.deployments.impl

import com.kubedroid.core.network.deployments.Deployment
import com.kubedroid.core.network.deployments.RolloutResult
import com.kubedroid.core.network.deployments.RolloutRevision
import com.kubedroid.core.network.deployments.ScaleResult
import com.kubedroid.feature.deployments.DeploymentListData
import com.kubedroid.feature.deployments.DeploymentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DefaultDeploymentRepository @Inject constructor(
    private val coreRepository: com.kubedroid.core.network.deployments.DeploymentRepository,
) : DeploymentRepository {

    override suspend fun list(namespace: String): Result<DeploymentListData> =
        coreRepository.list(namespace).map { list ->
            DeploymentListData(
                items = list.deployments,
                staleDataIndicator = list.staleDataIndicator,
            )
        }

    override fun watch(namespace: String): Flow<Result<DeploymentListData>> =
        coreRepository.watch(namespace).map { result ->
            result.map { list ->
                DeploymentListData(
                    items = list.deployments,
                    staleDataIndicator = list.staleDataIndicator,
                )
            }
        }

    override suspend fun scale(
        namespace: String,
        deploymentName: String,
        replicas: Int,
    ): ScaleResult = coreRepository.scale(namespace, deploymentName, replicas)

    override suspend fun history(
        namespace: String,
        deploymentName: String,
    ): Result<List<RolloutRevision>> = coreRepository.history(namespace, deploymentName)

    override suspend fun rollback(
        namespace: String,
        deploymentName: String,
        toRevision: Long?,
    ): RolloutResult = coreRepository.rollback(namespace, deploymentName, toRevision)

    override suspend fun pause(
        namespace: String,
        deploymentName: String,
    ): RolloutResult = coreRepository.pause(namespace, deploymentName)

    override suspend fun resume(
        namespace: String,
        deploymentName: String,
    ): RolloutResult = coreRepository.resume(namespace, deploymentName)
}
