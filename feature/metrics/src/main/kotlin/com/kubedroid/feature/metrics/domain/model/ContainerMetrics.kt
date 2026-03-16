package com.kubedroid.feature.metrics.domain.model

/**
 * Stub container-level metrics model.
 */
data class ContainerMetrics(
    val name: String,
    val points: List<MetricPoint> = emptyList(),
)
