package com.kubedroid.core.network.networking

import kotlinx.coroutines.flow.Flow

/**
 * Contract for ingress/network-policy and service URL operations.
 */
interface NetworkRepository {

    /**
     * Returns ingresses in the provided namespace.
     */
    suspend fun listIngresses(namespace: String): Result<List<Ingress>>

    /**
     * Watches ingress changes for the provided namespace.
     */
    fun watchIngresses(namespace: String): Flow<Result<List<Ingress>>>

    /**
     * Returns network policies in the provided namespace.
     */
    suspend fun listNetworkPolicies(namespace: String): Result<List<NetworkPolicy>>

    /**
     * Returns an externally reachable URL for the service when available.
     */
    suspend fun getServiceUrl(
        service: String,
        namespace: String,
    ): Result<String>
}
