package com.kubedroid.core.network.kubeconfig

/**
 * Contract for loading and persisting kubeconfig data and managing active contexts.
 */
interface KubeConfigRepository {

    /**
     * Loads kubeconfig from storage.
     */
    suspend fun load(): Result<KubeConfig>

    /**
     * Saves kubeconfig to storage.
     */
    suspend fun save(config: KubeConfig): Result<Unit>

    /**
     * Deletes kubeconfig from storage.
     */
    suspend fun delete(): Result<Unit>

    /**
     * Lists available contexts from the currently stored kubeconfig.
     */
    suspend fun listContexts(): Result<List<KubeContext>>

    /**
     * Sets the active context by name.
     */
    suspend fun setActiveContext(contextName: String): Result<Unit>
}
