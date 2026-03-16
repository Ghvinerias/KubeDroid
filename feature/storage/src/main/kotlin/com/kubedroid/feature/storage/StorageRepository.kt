package com.kubedroid.feature.storage

import com.kubedroid.core.network.storage.PersistentVolume
import com.kubedroid.core.network.storage.PersistentVolumeClaim
import com.kubedroid.core.network.storage.StorageClass

interface StorageRepository {
    suspend fun listPvs(): Result<List<PersistentVolume>>

    suspend fun listPvcs(namespace: String): Result<List<PersistentVolumeClaim>>

    suspend fun listStorageClasses(): Result<List<StorageClass>>
}
