package com.kubedroid.core.network.storage

import kotlinx.coroutines.flow.Flow

/**
 * Contract for PersistentVolume, PersistentVolumeClaim, and StorageClass operations.
 */
interface PvRepository {

    /**
     * Returns all cluster PersistentVolumes.
     */
    suspend fun listPvs(): Result<List<PersistentVolume>>

    /**
     * Returns PersistentVolumeClaims in the provided namespace.
     */
    suspend fun listPvcs(namespace: String): Result<List<PersistentVolumeClaim>>

    /**
     * Watches PersistentVolumeClaim changes for the provided namespace.
     */
    fun watchPvcs(namespace: String): Flow<Result<List<PersistentVolumeClaim>>>

    /**
     * Returns all cluster StorageClasses.
     */
    suspend fun listStorageClasses(): Result<List<StorageClass>>
}
