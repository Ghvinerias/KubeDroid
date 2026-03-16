package com.kubedroid.feature.settings

data class ClusterSummary(
    val contextName: String,
    val podCount: Int,
    val nodeCount: Int,
    val warningEventCount: Int,
    val cpuUsagePct: Float,
    val memoryUsagePct: Float,
    val health: ClusterHealth,
)

enum class ClusterHealth {
    Healthy,
    Degraded,
    Unreachable,
}
