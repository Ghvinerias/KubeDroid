package com.kubedroid.feature.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.kubedroid.feature.settings.ClusterHealth
import com.kubedroid.feature.settings.ClusterSummary
import com.kubedroid.feature.settings.MultiClusterRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetRepositoryImplTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("widget_sync_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("widget_ui_preferences", Context.MODE_PRIVATE).edit().clear().commit()

        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build(),
        )
    }

    @Test
    fun scheduleBackgroundSync_enqueuesPeriodicSyncWork() = runTest {
        val repository = WidgetRepositoryImpl(
            appContext = context,
            multiClusterRepository = FakeMultiClusterRepository(emptyList()),
            notificationDispatcher = RecordingNotifier(),
        )

        repository.scheduleBackgroundSync(
            NotificationConfig(
                crashLoopEnabled = true,
                nodeNotReadyEnabled = true,
                warningThreshold = 1,
                refreshIntervalMinutes = 15,
            ),
        )

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("widget_periodic_sync")
            .get()

        assertEquals(1, infos.size)
        assertTrue(infos.first().state.isFinished.not())
    }

    @Test
    fun runSync_notifiesWhenStateChanges() = runTest {
        val notifier = RecordingNotifier()
        val repository = WidgetRepositoryImpl(
            appContext = context,
            multiClusterRepository = FakeMultiClusterRepository(
                listOf(
                    ClusterSummary(
                        contextName = "cluster-a",
                        podCount = 10,
                        nodeCount = 3,
                        warningEventCount = 2,
                        cpuUsagePct = 12f,
                        memoryUsagePct = 20f,
                        health = ClusterHealth.Healthy,
                    ),
                ),
            ),
            notificationDispatcher = notifier,
        )

        WidgetSyncPreferences.saveConfig(
            context,
            NotificationConfig(
                crashLoopEnabled = true,
                nodeNotReadyEnabled = true,
                warningThreshold = 1,
                refreshIntervalMinutes = 15,
            ),
        )
        WidgetSyncPreferences.saveSnapshot(
            context,
            WidgetState(
                clusterName = "cluster-a",
                podCount = 10,
                warningCount = 0,
                nodeStatus = ClusterHealth.Healthy.name,
                lastUpdated = Instant.EPOCH,
            ),
        )

        val syncResult = repository.runSync()

        assertTrue(syncResult)
        assertEquals(1, notifier.notifications.size)
        assertEquals(WidgetAlertReason.WarningThresholdReached, notifier.notifications.single().reason)
    }

    @Test
    fun runSync_doesNotNotifyWhenStateDoesNotChange() = runTest {
        val notifier = RecordingNotifier()
        val repository = WidgetRepositoryImpl(
            appContext = context,
            multiClusterRepository = FakeMultiClusterRepository(
                listOf(
                    ClusterSummary(
                        contextName = "cluster-a",
                        podCount = 10,
                        nodeCount = 3,
                        warningEventCount = 1,
                        cpuUsagePct = 12f,
                        memoryUsagePct = 20f,
                        health = ClusterHealth.Healthy,
                    ),
                ),
            ),
            notificationDispatcher = notifier,
        )

        WidgetSyncPreferences.saveConfig(
            context,
            NotificationConfig(
                crashLoopEnabled = true,
                nodeNotReadyEnabled = true,
                warningThreshold = 2,
                refreshIntervalMinutes = 15,
            ),
        )
        WidgetSyncPreferences.saveSnapshot(
            context,
            WidgetState(
                clusterName = "cluster-a",
                podCount = 10,
                warningCount = 1,
                nodeStatus = ClusterHealth.Healthy.name,
                lastUpdated = Instant.EPOCH,
            ),
        )

        val syncResult = repository.runSync()

        assertTrue(syncResult)
        assertTrue(notifier.notifications.isEmpty())
    }

    private class FakeMultiClusterRepository(
        private val summaries: List<ClusterSummary>,
    ) : MultiClusterRepository {
        override fun getAllClusterSummaries(): Flow<List<ClusterSummary>> = flowOf(summaries)
    }

    private class RecordingNotifier : WidgetAlertNotifier {
        val notifications = mutableListOf<NotificationRecord>()

        override fun notifyClusterChange(
            clusterName: String,
            reason: WidgetAlertReason,
            currentState: WidgetState,
            config: NotificationConfig,
        ) {
            notifications += NotificationRecord(clusterName, reason, currentState, config)
        }
    }

    private data class NotificationRecord(
        val clusterName: String,
        val reason: WidgetAlertReason,
        val currentState: WidgetState,
        val config: NotificationConfig,
    )
}
