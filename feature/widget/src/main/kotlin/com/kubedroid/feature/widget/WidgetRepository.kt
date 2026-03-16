package com.kubedroid.feature.widget

/**
 * Feature-facing contract for widget data and background sync orchestration.
 */
interface WidgetRepository {
    suspend fun getWidgetState(contextName: String): Result<WidgetState>

    suspend fun scheduleBackgroundSync(config: NotificationConfig)
}
