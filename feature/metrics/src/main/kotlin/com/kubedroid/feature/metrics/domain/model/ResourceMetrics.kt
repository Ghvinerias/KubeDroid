package com.kubedroid.feature.metrics.domain.model

/**
 * Stub pod metrics model with per-container samples.
 */
data class ResourceMetrics(
    val podName: String,
    val namespace: String,
    val containers: List<ContainerMetrics> = emptyList(),
)
