package com.kubedroid.feature.metrics.domain.model

/**
 * Stub metric sample for CPU and memory usage at a point in time.
 */
data class MetricPoint(
    val timestamp: Long,
    val cpuMillicores: Long,
    val memoryBytes: Long,
)
