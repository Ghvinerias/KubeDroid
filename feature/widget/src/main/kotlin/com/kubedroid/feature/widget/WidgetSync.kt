package com.kubedroid.feature.widget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

interface WidgetSyncCoordinator {
    suspend fun runSync(): Boolean
}

enum class WidgetAlertReason {
    WarningThresholdReached,
    HealthDegraded,
    NodeAvailabilityChanged,
}

internal object WidgetSyncScheduler {
    private const val UNIQUE_WORK_NAME = "widget_periodic_sync"
    private const val MIN_PERIODIC_INTERVAL_MINUTES = 15L

    fun schedule(
        context: Context,
        config: NotificationConfig,
    ) {
        val intervalMinutes = config.refreshIntervalMinutes
            .coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES.toInt())
            .toLong()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(
            intervalMinutes,
            TimeUnit.MINUTES,
        ).setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

object WidgetSyncBootstrap {
    fun onAppStart(context: Context) {
        val config = WidgetSyncPreferences.loadConfig(context) ?: return
        WidgetSyncScheduler.schedule(context = context, config = config)
    }

    fun onDeviceBoot(context: Context) {
        val config = WidgetSyncPreferences.loadConfig(context) ?: return
        WidgetSyncScheduler.schedule(context = context, config = config)
    }
}

class WidgetNotificationDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) : WidgetAlertNotifier {

    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    override fun notifyClusterChange(
        clusterName: String,
        reason: WidgetAlertReason,
        currentState: WidgetState,
        config: NotificationConfig,
    ) {
        if (!canPostNotifications()) {
            return
        }

        val channelId = buildChannelId(clusterName)
        ensureChannel(clusterName = clusterName, channelId = channelId)

        val notificationId = clusterName.hashCode()
        val title = when (reason) {
            WidgetAlertReason.WarningThresholdReached -> context.getString(R.string.widget_notification_title_warning)
            WidgetAlertReason.HealthDegraded -> context.getString(R.string.widget_notification_title_health)
            WidgetAlertReason.NodeAvailabilityChanged -> context.getString(R.string.widget_notification_title_node)
        }
        val content = when (reason) {
            WidgetAlertReason.WarningThresholdReached -> context.getString(
                R.string.widget_notification_content_warning,
                clusterName,
                currentState.warningCount,
                config.warningThreshold,
            )

            WidgetAlertReason.HealthDegraded -> context.getString(
                R.string.widget_notification_content_health,
                clusterName,
                currentState.nodeStatus,
            )

            WidgetAlertReason.NodeAvailabilityChanged -> context.getString(
                R.string.widget_notification_content_node,
                clusterName,
                currentState.nodeStatus,
            )
        }

        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setWhen(Instant.now().toEpochMilli())
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun ensureChannel(clusterName: String, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            channelId,
            context.getString(R.string.widget_notification_channel_name, clusterName),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.widget_notification_channel_description, clusterName)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildChannelId(clusterName: String): String {
        return "widget_cluster_" + clusterName
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .ifBlank { "default" }
    }
}

interface WidgetAlertNotifier {
    fun notifyClusterChange(
        clusterName: String,
        reason: WidgetAlertReason,
        currentState: WidgetState,
        config: NotificationConfig,
    )
}

internal object WidgetSyncPreferences {
    private const val PREFS_NAME = "widget_sync_preferences"

    private const val KEY_CRASH_LOOP_ENABLED = "crash_loop_enabled"
    private const val KEY_NODE_NOT_READY_ENABLED = "node_not_ready_enabled"
    private const val KEY_WARNING_THRESHOLD = "warning_threshold"
    private const val KEY_REFRESH_INTERVAL_MINUTES = "refresh_interval_minutes"
    private const val KEY_CONFIG_PRESENT = "config_present"

    private const val KEY_PREFIX_SNAPSHOT = "cluster_snapshot_"

    fun saveConfig(
        context: Context,
        config: NotificationConfig,
    ) {
        prefs(context).edit()
            .putBoolean(KEY_CONFIG_PRESENT, true)
            .putBoolean(KEY_CRASH_LOOP_ENABLED, config.crashLoopEnabled)
            .putBoolean(KEY_NODE_NOT_READY_ENABLED, config.nodeNotReadyEnabled)
            .putInt(KEY_WARNING_THRESHOLD, config.warningThreshold)
            .putInt(KEY_REFRESH_INTERVAL_MINUTES, config.refreshIntervalMinutes)
            .apply()
    }

    fun loadConfig(context: Context): NotificationConfig? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_CONFIG_PRESENT, false)) {
            return null
        }

        return NotificationConfig(
            crashLoopEnabled = prefs.getBoolean(KEY_CRASH_LOOP_ENABLED, true),
            nodeNotReadyEnabled = prefs.getBoolean(KEY_NODE_NOT_READY_ENABLED, true),
            warningThreshold = prefs.getInt(KEY_WARNING_THRESHOLD, 1),
            refreshIntervalMinutes = prefs.getInt(KEY_REFRESH_INTERVAL_MINUTES, 15),
        )
    }

    fun saveSnapshot(
        context: Context,
        state: WidgetState,
    ) {
        val encoded = listOf(
            state.podCount.toString(),
            state.warningCount.toString(),
            state.nodeStatus,
            state.lastUpdated.toEpochMilli().toString(),
        ).joinToString("|")

        prefs(context).edit()
            .putString(snapshotKey(state.clusterName), encoded)
            .apply()
    }

    fun loadSnapshot(
        context: Context,
        clusterName: String,
    ): WidgetState? {
        val encoded = prefs(context).getString(snapshotKey(clusterName), null) ?: return null
        val fields = encoded.split("|")
        if (fields.size < 4) return null

        val podCount = fields[0].toIntOrNull() ?: return null
        val warningCount = fields[1].toIntOrNull() ?: return null
        val nodeStatus = fields[2]
        val lastUpdatedMs = fields[3].toLongOrNull() ?: return null

        return WidgetState(
            clusterName = clusterName,
            podCount = podCount,
            warningCount = warningCount,
            nodeStatus = nodeStatus,
            lastUpdated = Instant.ofEpochMilli(lastUpdatedMs),
        )
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun snapshotKey(clusterName: String): String {
        return KEY_PREFIX_SNAPSHOT + clusterName
    }
}

class WidgetSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                WidgetSyncWorkerEntryPoint::class.java,
            )

            val shouldSucceed = entryPoint.widgetSyncCoordinator().runSync()
            if (shouldSucceed) Result.success() else Result.retry()
        } catch (exception: Throwable) {
            if (exception is CancellationException) throw exception
            Result.retry()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetSyncWorkerEntryPoint {
    fun widgetSyncCoordinator(): WidgetSyncCoordinator
}
