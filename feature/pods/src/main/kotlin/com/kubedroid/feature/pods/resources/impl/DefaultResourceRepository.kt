package com.kubedroid.feature.pods.resources.impl

import com.kubedroid.core.network.namespace.ALL_NAMESPACES
import com.kubedroid.core.network.pods.NamespaceRepository
import com.kubedroid.core.network.pods.PodRepository
import com.kubedroid.core.network.pods.PodStatus
import com.kubedroid.feature.pods.resources.ResourceListData
import com.kubedroid.feature.pods.resources.ResourceListItem
import com.kubedroid.feature.pods.resources.ResourceRepository
import com.kubedroid.feature.pods.resources.ResourceStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultResourceRepository @Inject constructor(
    private val podRepository: PodRepository,
    private val namespaceRepository: NamespaceRepository,
) : ResourceRepository {

    override suspend fun listNamespaces(): Result<List<String>> {
        return namespaceRepository.listNamespaces()
    }

    override suspend fun listResources(namespace: String?): Result<ResourceListData> {
        val targetNamespace = namespace?.trim().takeUnless { it.isNullOrEmpty() } ?: ALL_NAMESPACES
        return podRepository.listPods(targetNamespace).mapCatching { podList ->
            ResourceListData(
                items = podList.pods.map { pod ->
                    ResourceListItem(
                        id = "pod:${pod.namespace}:${pod.name}",
                        name = pod.name,
                        namespace = pod.namespace,
                        kind = "Pod",
                        status = pod.status.toResourceStatus(),
                    )
                },
                staleDataIndicator = podList.staleDataIndicator,
            )
        }
    }

    private fun PodStatus.toResourceStatus(): ResourceStatus {
        return when (this) {
            PodStatus.Running,
            PodStatus.Succeeded,
            -> ResourceStatus.HEALTHY

            PodStatus.Pending,
            PodStatus.Unknown,
            -> ResourceStatus.WARNING

            PodStatus.Failed -> ResourceStatus.ERROR
        }
    }
}
