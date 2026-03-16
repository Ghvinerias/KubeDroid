package com.kubedroid.feature.widget

import java.time.Instant

/**
 * Snapshot of data rendered in the home screen widget.
 */
data class WidgetState(
    val clusterName: String,
    val podCount: Int,
    val warningCount: Int,
    val nodeStatus: String,
    val lastUpdated: Instant,
)

/**
 * User-configurable notification and refresh settings for the widget.
 */
data class NotificationConfig(
    val crashLoopEnabled: Boolean,
    val nodeNotReadyEnabled: Boolean,
    val warningThreshold: Int,
    val refreshIntervalMinutes: Int,
)
