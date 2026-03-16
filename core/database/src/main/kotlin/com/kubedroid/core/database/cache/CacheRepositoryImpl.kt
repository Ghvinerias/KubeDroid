package com.kubedroid.core.database.cache

import androidx.room.withTransaction

class CacheRepositoryImpl(
    private val cacheDatabase: CacheDatabase,
    private val nowProviderMillis: () -> Long = { System.currentTimeMillis() },
) : CacheRepository {

    private val cacheDao: CacheDao
        get() = cacheDatabase.cacheDao()

    override suspend fun cacheResources(
        contextName: String,
        pods: List<CachedPod>,
        deployments: List<CachedDeployment>,
        nodes: List<CachedNode>,
        namespaces: List<CachedNamespace>,
        fetchedAt: Long,
    ): Result<Unit> = runCatching {
        val normalizedContextName = contextName.trim()
        require(normalizedContextName.isNotEmpty()) { "Context name must not be empty" }

        cacheDatabase.withTransaction {
            if (pods.isNotEmpty()) {
                pods.asSequence().map { it.namespace }.distinct().forEach { namespace ->
                    cacheDao.deletePodsInNamespace(normalizedContextName, namespace)
                }
                cacheDao.upsertPods(
                    pods.map { it.copy(contextName = normalizedContextName, lastFetchedAt = fetchedAt) },
                )
            }

            if (deployments.isNotEmpty()) {
                deployments.asSequence().map { it.namespace }.distinct().forEach { namespace ->
                    cacheDao.deleteDeploymentsInNamespace(normalizedContextName, namespace)
                }
                cacheDao.upsertDeployments(
                    deployments.map { it.copy(contextName = normalizedContextName, lastFetchedAt = fetchedAt) },
                )
            }

            if (nodes.isNotEmpty()) {
                cacheDao.deleteNodes(normalizedContextName)
                cacheDao.upsertNodes(
                    nodes.map { it.copy(contextName = normalizedContextName, lastFetchedAt = fetchedAt) },
                )
            }

            if (namespaces.isNotEmpty()) {
                cacheDao.deleteNamespaces(normalizedContextName)
                cacheDao.upsertNamespaces(
                    namespaces.map { it.copy(contextName = normalizedContextName, lastFetchedAt = fetchedAt) },
                )
            }
        }
    }

    override suspend fun getCachedResources(
        contextName: String,
        namespace: String?,
    ): Result<CachedResources> = runCatching {
        val normalizedContextName = contextName.trim()
        require(normalizedContextName.isNotEmpty()) { "Context name must not be empty" }

        val trimmedNamespace = namespace?.trim()?.takeIf { it.isNotEmpty() }
        val pods = if (trimmedNamespace == null) {
            cacheDao.getAllPods(normalizedContextName)
        } else {
            cacheDao.getPods(normalizedContextName, trimmedNamespace)
        }
        val deployments = if (trimmedNamespace == null) {
            cacheDao.getAllDeployments(normalizedContextName)
        } else {
            cacheDao.getDeployments(normalizedContextName, trimmedNamespace)
        }

        CachedResources(
            pods = pods,
            deployments = deployments,
            nodes = cacheDao.getNodes(normalizedContextName),
            namespaces = cacheDao.getNamespaces(normalizedContextName),
        )
    }

    override suspend fun getCacheAge(contextName: String): Result<StaleDataIndicator> = runCatching {
        val normalizedContextName = contextName.trim()
        require(normalizedContextName.isNotEmpty()) { "Context name must not be empty" }

        val lastFetchedAt = cacheDao.getLatestFetchedAt(normalizedContextName)
        val now = nowProviderMillis()
        val isFresh = lastFetchedAt != null && now - lastFetchedAt <= MAX_CACHE_AGE_MILLIS

        StaleDataIndicator(
            isFresh = isFresh,
            lastFetchedAt = lastFetchedAt,
            source = StaleDataIndicator.Source.Cache,
        )
    }

    override suspend fun clearCache(contextName: String?): Result<Unit> = runCatching {
        val normalizedContextName = contextName?.trim()?.takeIf { it.isNotEmpty() }

        cacheDatabase.withTransaction {
            if (normalizedContextName == null) {
                cacheDao.clearAllPods()
                cacheDao.clearAllDeployments()
                cacheDao.clearAllNodes()
                cacheDao.clearAllNamespaces()
            } else {
                cacheDao.clearPods(normalizedContextName)
                cacheDao.clearDeployments(normalizedContextName)
                cacheDao.clearNodes(normalizedContextName)
                cacheDao.clearNamespaces(normalizedContextName)
            }
        }
    }
}

private const val MAX_CACHE_AGE_MILLIS = 120_000L
