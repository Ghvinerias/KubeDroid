package com.kubedroid.core.network.pods

/**
 * Contract for retrieving cluster namespaces.
 */
interface NamespaceRepository {

    /**
     * Returns the available namespace names.
     */
    suspend fun listNamespaces(): Result<List<String>>
}
