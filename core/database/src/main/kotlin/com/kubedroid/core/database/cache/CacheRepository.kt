package com.kubedroid.core.database.cache

interface CacheRepository {
    suspend fun cacheResources(
        contextName: String,
        pods: List<CachedPod> = emptyList(),
        deployments: List<CachedDeployment> = emptyList(),
        nodes: List<CachedNode> = emptyList(),
        namespaces: List<CachedNamespace> = emptyList(),
        fetchedAt: Long = System.currentTimeMillis(),
    ): Result<Unit>

    suspend fun getCachedResources(
        contextName: String,
        namespace: String? = null,
    ): Result<CachedResources>

    suspend fun getCacheAge(contextName: String): Result<StaleDataIndicator>

    suspend fun clearCache(contextName: String? = null): Result<Unit>
}
