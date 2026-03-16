package com.kubedroid.feature.helm.domain.repository

import com.kubedroid.feature.helm.domain.model.HelmRelease
import com.kubedroid.feature.helm.domain.model.HelmRevision

interface HelmRepository {
    suspend fun listReleases(namespace: String): List<HelmRelease>

    suspend fun getReleaseHistory(
        name: String,
        namespace: String,
    ): List<HelmRevision>

    suspend fun rollbackRelease(
        name: String,
        namespace: String,
        revision: Int,
    )

    suspend fun getReleaseManifest(
        name: String,
        namespace: String,
    ): String
}
