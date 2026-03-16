package com.kubedroid.feature.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

data class NotificationSettingsUiState(
    val crashLoopEnabled: Boolean = true,
    val nodeNotReadyEnabled: Boolean = true,
    val warningThreshold: Int = 1,
    val cpuThresholdPercent: Int = 80,
    val memoryThresholdPercent: Int = 80,
    val refreshIntervalMinutes: Int = 15,
)

@Composable
fun NotificationSettingsScreen(
    initialState: NotificationSettingsUiState = NotificationSettingsUiState(),
    refreshIntervalOptions: List<Int> = listOf(15, 30, 60),
    onSave: (NotificationSettingsUiState) -> Unit,
    headerContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var state by remember(initialState) { mutableStateOf(initialState) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        headerContent?.invoke()

        Text(
            text = stringResource(id = R.string.widget_notification_settings_title),
            style = MaterialTheme.typography.titleLarge,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.widget_notification_type_header),
                    style = MaterialTheme.typography.titleMedium,
                )

                SettingSwitchRow(
                    label = stringResource(id = R.string.widget_notification_type_crash_loop),
                    checked = state.crashLoopEnabled,
                    onCheckedChange = { state = state.copy(crashLoopEnabled = it) },
                )
                SettingSwitchRow(
                    label = stringResource(id = R.string.widget_notification_type_node_not_ready),
                    checked = state.nodeNotReadyEnabled,
                    onCheckedChange = { state = state.copy(nodeNotReadyEnabled = it) },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.widget_threshold_header),
                    style = MaterialTheme.typography.titleMedium,
                )
                SliderRow(
                    label = stringResource(
                        id = R.string.widget_threshold_warning_count,
                        state.warningThreshold,
                    ),
                    value = state.warningThreshold.toFloat(),
                    valueRange = 1f..10f,
                    steps = 8,
                    onValueChange = { state = state.copy(warningThreshold = it.toInt()) },
                )
                SliderRow(
                    label = stringResource(
                        id = R.string.widget_threshold_cpu,
                        state.cpuThresholdPercent,
                    ),
                    value = state.cpuThresholdPercent.toFloat(),
                    valueRange = 50f..100f,
                    steps = 9,
                    onValueChange = { state = state.copy(cpuThresholdPercent = it.toInt()) },
                )
                SliderRow(
                    label = stringResource(
                        id = R.string.widget_threshold_memory,
                        state.memoryThresholdPercent,
                    ),
                    value = state.memoryThresholdPercent.toFloat(),
                    valueRange = 50f..100f,
                    steps = 9,
                    onValueChange = { state = state.copy(memoryThresholdPercent = it.toInt()) },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.widget_refresh_interval_header),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    refreshIntervalOptions.forEach { minutes ->
                        Button(
                            onClick = { state = state.copy(refreshIntervalMinutes = minutes) },
                        ) {
                            val label = if (state.refreshIntervalMinutes == minutes) {
                                stringResource(id = R.string.widget_refresh_interval_selected, minutes)
                            } else {
                                stringResource(id = R.string.widget_refresh_interval, minutes)
                            }
                            Text(text = label)
                        }
                    }
                }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onSave(state) },
        ) {
            Text(text = stringResource(id = R.string.widget_save_settings))
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
