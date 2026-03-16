package com.kubedroid.feature.settings

import kotlinx.coroutines.flow.Flow

interface MultiClusterRepository {
    fun getAllClusterSummaries(): Flow<List<ClusterSummary>>
}
