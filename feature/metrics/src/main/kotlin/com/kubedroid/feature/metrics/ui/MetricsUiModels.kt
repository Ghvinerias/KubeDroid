package com.kubedroid.feature.metrics.ui

data class MetricSeriesPoint(
    val timestampMillis: Long,
    val value: Float,
)

data class ResourceQuotaUsageItem(
    val name: String,
    val used: Float,
    val hard: Float,
    val usedDisplay: String,
    val hardDisplay: String,
)
