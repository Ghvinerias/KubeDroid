package com.kubedroid.core.database.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPods(pods: List<CachedPod>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeployments(deployments: List<CachedDeployment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodes(nodes: List<CachedNode>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNamespaces(namespaces: List<CachedNamespace>)

    @Query("SELECT * FROM cached_pods WHERE contextName = :contextName AND namespace = :namespace")
    suspend fun getPods(contextName: String, namespace: String): List<CachedPod>

    @Query("SELECT * FROM cached_pods WHERE contextName = :contextName")
    suspend fun getAllPods(contextName: String): List<CachedPod>

    @Query("SELECT * FROM cached_deployments WHERE contextName = :contextName AND namespace = :namespace")
    suspend fun getDeployments(contextName: String, namespace: String): List<CachedDeployment>

    @Query("SELECT * FROM cached_deployments WHERE contextName = :contextName")
    suspend fun getAllDeployments(contextName: String): List<CachedDeployment>

    @Query("SELECT * FROM cached_nodes WHERE contextName = :contextName")
    suspend fun getNodes(contextName: String): List<CachedNode>

    @Query("SELECT * FROM cached_namespaces WHERE contextName = :contextName")
    suspend fun getNamespaces(contextName: String): List<CachedNamespace>

    @Query("DELETE FROM cached_pods WHERE contextName = :contextName AND namespace = :namespace")
    suspend fun deletePodsInNamespace(contextName: String, namespace: String)

    @Query("DELETE FROM cached_deployments WHERE contextName = :contextName AND namespace = :namespace")
    suspend fun deleteDeploymentsInNamespace(contextName: String, namespace: String)

    @Query("DELETE FROM cached_nodes WHERE contextName = :contextName")
    suspend fun deleteNodes(contextName: String)

    @Query("DELETE FROM cached_namespaces WHERE contextName = :contextName")
    suspend fun deleteNamespaces(contextName: String)

    @Query("DELETE FROM cached_pods WHERE contextName = :contextName")
    suspend fun clearPods(contextName: String)

    @Query("DELETE FROM cached_deployments WHERE contextName = :contextName")
    suspend fun clearDeployments(contextName: String)

    @Query("DELETE FROM cached_nodes WHERE contextName = :contextName")
    suspend fun clearNodes(contextName: String)

    @Query("DELETE FROM cached_namespaces WHERE contextName = :contextName")
    suspend fun clearNamespaces(contextName: String)

    @Query("DELETE FROM cached_pods")
    suspend fun clearAllPods()

    @Query("DELETE FROM cached_deployments")
    suspend fun clearAllDeployments()

    @Query("DELETE FROM cached_nodes")
    suspend fun clearAllNodes()

    @Query("DELETE FROM cached_namespaces")
    suspend fun clearAllNamespaces()

    @Query(
        """
        SELECT MAX(lastFetchedAt) FROM (
            SELECT MAX(lastFetchedAt) AS lastFetchedAt FROM cached_pods WHERE contextName = :contextName
            UNION ALL
            SELECT MAX(lastFetchedAt) AS lastFetchedAt FROM cached_deployments WHERE contextName = :contextName
            UNION ALL
            SELECT MAX(lastFetchedAt) AS lastFetchedAt FROM cached_nodes WHERE contextName = :contextName
            UNION ALL
            SELECT MAX(lastFetchedAt) AS lastFetchedAt FROM cached_namespaces WHERE contextName = :contextName
        )
        """,
    )
    suspend fun getLatestFetchedAt(contextName: String): Long?
}
