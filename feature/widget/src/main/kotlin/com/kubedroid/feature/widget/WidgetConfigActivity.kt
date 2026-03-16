package com.kubedroid.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.kubedroid.feature.settings.ClusterSummary
import com.kubedroid.feature.settings.MultiClusterRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    @Inject
    lateinit var multiClusterRepository: MultiClusterRepository

    @Inject
    lateinit var widgetRepository: WidgetRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appWidgetId = extractAppWidgetId(intent)

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setResult(RESULT_CANCELED)

        val currentConfig = WidgetSyncPreferences.loadConfig(this)
        val initialSettings = currentConfig.toInitialSettings()
        val initialCluster = WidgetUiPreferences.getWidgetCluster(this, appWidgetId)

        lifecycleScope.launch {
            val summaries = multiClusterRepository.getAllClusterSummaries().first()
            setContent {
                MaterialTheme {
                    WidgetConfigScreen(
                        clusterSummaries = summaries,
                        initialSelectedCluster = initialCluster,
                        initialSettings = initialSettings,
                        onSave = { selectedCluster, settings ->
                            persistWidgetConfiguration(
                                appWidgetId = appWidgetId,
                                selectedCluster = selectedCluster,
                                settings = settings,
                            )
                        },
                    )
                }
            }
        }
    }

    private fun persistWidgetConfiguration(
        appWidgetId: Int,
        selectedCluster: String?,
        settings: NotificationSettingsUiState,
    ) {
        if (selectedCluster.isNullOrBlank()) {
            Toast.makeText(
                this,
                getString(R.string.widget_select_cluster_required),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        lifecycleScope.launch {
            WidgetUiPreferences.setWidgetCluster(
                context = this@WidgetConfigActivity,
                appWidgetId = appWidgetId,
                clusterName = selectedCluster,
            )

            widgetRepository.scheduleBackgroundSync(
                NotificationConfig(
                    crashLoopEnabled = settings.crashLoopEnabled,
                    nodeNotReadyEnabled = settings.nodeNotReadyEnabled,
                    warningThreshold = settings.warningThreshold,
                    refreshIntervalMinutes = settings.refreshIntervalMinutes,
                ),
            )

            val appWidgetManager = AppWidgetManager.getInstance(this@WidgetConfigActivity)
            KubeDroidWidget.updateWidget(
                context = this@WidgetConfigActivity,
                appWidgetManager = appWidgetManager,
                appWidgetId = appWidgetId,
            )

            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
            finish()
        }
    }
}

internal fun extractAppWidgetId(intent: Intent?): Int {
    return intent?.extras?.getInt(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID,
    ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
}

internal fun NotificationConfig?.toInitialSettings(): NotificationSettingsUiState {
    return NotificationSettingsUiState(
        crashLoopEnabled = this?.crashLoopEnabled ?: true,
        nodeNotReadyEnabled = this?.nodeNotReadyEnabled ?: true,
        warningThreshold = this?.warningThreshold ?: 1,
        refreshIntervalMinutes = this?.refreshIntervalMinutes ?: 15,
    )
}

@Composable
private fun WidgetConfigScreen(
    clusterSummaries: List<ClusterSummary>,
    initialSelectedCluster: String?,
    initialSettings: NotificationSettingsUiState,
    onSave: (selectedCluster: String?, settings: NotificationSettingsUiState) -> Unit,
) {
    var selectedCluster by remember(initialSelectedCluster) { mutableStateOf(initialSelectedCluster) }
    NotificationSettingsScreen(
        initialState = initialSettings,
        onSave = { settings ->
            onSave(selectedCluster, settings)
        },
        headerContent = {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(id = R.string.widget_config_cluster_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (clusterSummaries.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.widget_config_no_clusters),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        clusterSummaries.forEach { summary ->
                            ClusterSelectionRow(
                                name = summary.contextName,
                                selected = selectedCluster == summary.contextName,
                                onSelect = { selectedCluster = summary.contextName },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ClusterSelectionRow(
    name: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
        )
        RadioButton(
            selected = selected,
            onClick = onSelect,
        )
    }
}
