package com.kubedroid.feature.pods.resources

import com.kubedroid.core.database.cache.StaleDataIndicator

data class ResourceListItem(
    val id: String,
    val stableKey: String = id,
    val name: String,
    val namespace: String,
    val kind: String,
    val status: ResourceStatus,
    val cpuMillicoresSeries: List<Long> = emptyList(),
    val memoryBytesSeries: List<Long> = emptyList(),
)

enum class ResourceStatus {
    HEALTHY,
    WARNING,
    ERROR,
    UNKNOWN,
}

interface ResourceRepository {
    suspend fun listNamespaces(): Result<List<String>>

    suspend fun listResources(namespace: String?): Result<ResourceListData>
}

data class ResourceListData(
    val items: List<ResourceListItem>,
    val staleDataIndicator: StaleDataIndicator,
)
