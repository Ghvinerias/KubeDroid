package com.kubedroid.feature.widget

import android.content.Context
import com.kubedroid.feature.settings.ClusterHealth
import com.kubedroid.feature.settings.ClusterSummary
import com.kubedroid.feature.settings.MultiClusterRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

@Singleton
class WidgetRepositoryImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val multiClusterRepository: MultiClusterRepository,
    private val notificationDispatcher: WidgetAlertNotifier,
) : WidgetRepository, WidgetSyncCoordinator {

    override suspend fun getWidgetState(contextName: String): Result<WidgetState> = withContext(Dispatchers.IO) {
        runCatching {
            val summary = loadClusterSummaries()
                .firstOrNull { it.contextName == contextName }
                ?: throw IllegalStateException("Cluster context '$contextName' not found")

            summary.toWidgetState(now = Instant.now())
        }
    }

    override suspend fun scheduleBackgroundSync(config: NotificationConfig) {
        WidgetSyncPreferences.saveConfig(context = appContext, config = config)
        WidgetSyncScheduler.schedule(context = appContext, config = config)
    }

    override suspend fun runSync(): Boolean = withContext(Dispatchers.IO) {
        val config = WidgetSyncPreferences.loadConfig(context = appContext) ?: return@withContext true

        val summaries = try {
            loadClusterSummaries()
        } catch (exception: Throwable) {
            if (exception is CancellationException) throw exception
            return@withContext false
        }

        val now = Instant.now()
        summaries.forEach { summary ->
            val currentState = summary.toWidgetState(now = now)
            val previousState = WidgetSyncPreferences.loadSnapshot(
                context = appContext,
                clusterName = currentState.clusterName,
            )

            if (previousState != null) {
                val reason = decideNotificationReason(
                    previous = previousState,
                    current = currentState,
                    config = config,
                )
                if (reason != null) {
                    notificationDispatcher.notifyClusterChange(
                        clusterName = currentState.clusterName,
                        reason = reason,
                        currentState = currentState,
                        config = config,
                    )
                }
            }

            WidgetSyncPreferences.saveSnapshot(context = appContext, state = currentState)
        }

        KubeDroidWidget.updateAllWidgets(appContext)
        true
    }

    private suspend fun loadClusterSummaries(): List<ClusterSummary> {
        return multiClusterRepository
            .getAllClusterSummaries()
            .toList()
            .lastOrNull()
            .orEmpty()
    }

    private fun decideNotificationReason(
        previous: WidgetState,
        current: WidgetState,
        config: NotificationConfig,
    ): WidgetAlertReason? {
        val warningThresholdCrossed =
            previous.warningCount < config.warningThreshold &&
                current.warningCount >= config.warningThreshold

        if (warningThresholdCrossed) {
            return WidgetAlertReason.WarningThresholdReached
        }

        val currentHealth = current.nodeStatus.toClusterHealth()
        val previousHealth = previous.nodeStatus.toClusterHealth()

        val healthRegressed = previousHealth == ClusterHealth.Healthy && currentHealth != ClusterHealth.Healthy
        if (config.crashLoopEnabled && healthRegressed) {
            return WidgetAlertReason.HealthDegraded
        }

        val nodeSignalDegraded =
            currentHealth == ClusterHealth.Unreachable && previousHealth != ClusterHealth.Unreachable
        if (config.nodeNotReadyEnabled && nodeSignalDegraded) {
            return WidgetAlertReason.NodeAvailabilityChanged
        }

        return null
    }
}

internal fun ClusterSummary.toWidgetState(now: Instant): WidgetState {
    return WidgetState(
        clusterName = contextName,
        podCount = podCount,
        warningCount = warningEventCount,
        nodeStatus = health.name,
        lastUpdated = now,
    )
}

private fun String.toClusterHealth(): ClusterHealth {
    return runCatching { ClusterHealth.valueOf(this) }
        .getOrDefault(ClusterHealth.Unreachable)
}
