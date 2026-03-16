package com.kubedroid.core.network.config

import kotlinx.coroutines.flow.Flow

/**
 * Contract for Secret list/watch/get and secure value decryption operations.
 */
interface SecretRepository {

    /**
     * Returns Secrets in the provided namespace.
     */
    suspend fun list(namespace: String): Result<List<Secret>>

    /**
     * Watches Secret changes for the provided namespace.
     */
    fun watch(namespace: String): Flow<Result<List<Secret>>>

    /**
     * Returns Secret metadata by [name] in the provided [namespace].
     */
    suspend fun get(
        name: String,
        namespace: String,
    ): Result<Secret>

    /**
     * Decrypts and returns a single secret value for [key].
     *
     * This operation must require biometric authentication before returning decrypted data.
     */
    suspend fun getDecryptedValue(
        name: String,
        namespace: String,
        key: String,
        biometricToken: BiometricToken,
    ): Result<String>
}
