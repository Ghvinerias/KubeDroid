package com.kubedroid.feature.storage.impl

import com.kubedroid.core.network.storage.PersistentVolume
import com.kubedroid.core.network.storage.PersistentVolumeClaim
import com.kubedroid.core.network.storage.PvRepository
import com.kubedroid.core.network.storage.StorageClass
import com.kubedroid.feature.storage.StorageRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultStorageRepository @Inject constructor(
    private val coreRepository: PvRepository,
) : StorageRepository {

    override suspend fun listPvs(): Result<List<PersistentVolume>> = coreRepository.listPvs()

    override suspend fun listPvcs(namespace: String): Result<List<PersistentVolumeClaim>> {
        return coreRepository.listPvcs(namespace)
    }

    override suspend fun listStorageClasses(): Result<List<StorageClass>> {
        return coreRepository.listStorageClasses()
    }
}
