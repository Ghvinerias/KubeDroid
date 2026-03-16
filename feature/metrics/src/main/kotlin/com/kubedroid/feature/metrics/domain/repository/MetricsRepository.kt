package com.kubedroid.feature.metrics.domain.repository

import com.kubedroid.feature.metrics.domain.model.MetricPoint
import com.kubedroid.feature.metrics.domain.model.ResourceMetrics
import kotlinx.coroutines.flow.Flow

/**
 * Stub metrics repository contract.
 */
interface MetricsRepository {
    suspend fun getPodMetrics(name: String, namespace: String): ResourceMetrics?

    suspend fun getNodeMetrics(nodeName: String): MetricPoint?

    fun watchPodMetrics(namespace: String): Flow<List<ResourceMetrics>>

    suspend fun isMetricsServerAvailable(): Boolean
}
