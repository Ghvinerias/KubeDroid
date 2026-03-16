package com.kubedroid.core.network.config

import kotlinx.coroutines.flow.Flow

/**
 * Contract for ConfigMap list/watch/get/update operations.
 */
interface ConfigMapRepository {

    /**
     * Returns ConfigMaps in the provided namespace.
     */
    suspend fun list(namespace: String): Result<List<ConfigMap>>

    /**
     * Watches ConfigMap changes for the provided namespace.
     */
    fun watch(namespace: String): Flow<Result<List<ConfigMap>>>

    /**
     * Returns a ConfigMap by [name] in the provided [namespace].
     */
    suspend fun get(
        name: String,
        namespace: String,
    ): Result<ConfigMap>

    /**
     * Updates a ConfigMap and returns the updated resource.
     */
    suspend fun update(configMap: ConfigMap): Result<ConfigMap>
}
