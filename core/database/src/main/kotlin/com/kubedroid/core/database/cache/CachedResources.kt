package com.kubedroid.core.database.cache

data class CachedResources(
    val pods: List<CachedPod> = emptyList(),
    val deployments: List<CachedDeployment> = emptyList(),
    val nodes: List<CachedNode> = emptyList(),
    val namespaces: List<CachedNamespace> = emptyList(),
)
