package com.kubedroid.feature.helm

import com.kubedroid.feature.helm.model.HelmRelease
import com.kubedroid.feature.helm.model.HelmRevision

interface HelmRepository {
    suspend fun listReleases(namespace: String): Result<List<HelmRelease>>

    suspend fun getReleaseHistory(
        name: String,
        namespace: String,
    ): Result<List<HelmRevision>>

    suspend fun rollbackRelease(
        name: String,
        namespace: String,
        revision: Int,
    ): Result<Unit>

    suspend fun getReleaseManifest(
        name: String,
        namespace: String,
    ): Result<String>
}
