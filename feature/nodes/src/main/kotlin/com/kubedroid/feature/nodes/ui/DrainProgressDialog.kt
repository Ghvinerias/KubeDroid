package com.kubedroid.feature.nodes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.kubedroid.feature.nodes.R

@Composable
fun DrainProgressDialog(
    state: DrainProgressUiState,
    onDismissRequest: () -> Unit,
) {
    if (!state.isVisible) {
        return
    }

    val contentSpacing = dimensionResource(id = R.dimen.nodes_section_spacing)
    val progress = if (state.totalPods > 0) {
        state.evictedPods.toFloat() / state.totalPods.toFloat()
    } else {
        0f
    }

    AlertDialog(
        onDismissRequest = {
            if (state.isCompleted) {
                onDismissRequest()
            }
        },
        title = {
            Text(text = stringResource(id = R.string.node_drain_progress_title, state.nodeName))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(contentSpacing)) {
                Text(
                    text = stringResource(
                        id = R.string.node_drain_progress_counter,
                        state.evictedPods,
                        state.totalPods,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = dimensionResource(id = R.dimen.nodes_drain_events_max_height)),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing),
                ) {
                    itemsIndexed(state.events) { _, event ->
                        Text(
                            text = event.toReadableText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = state.isCompleted,
            ) {
                Text(
                    text = stringResource(
                        id = if (state.isCompleted) {
                            R.string.node_drain_progress_close
                        } else {
                            R.string.node_drain_progress_running
                        },
                    ),
                )
            }
        },
    )
}

@Composable
private fun DrainProgressEventUi.toReadableText(): String {
    return when (messageType) {
        DrainEventMessageType.STARTED -> stringResource(id = R.string.node_drain_event_started)
        DrainEventMessageType.EVICTION_ATTEMPT -> stringResource(
            id = R.string.node_drain_event_eviction_attempt,
            namespace.orEmpty(),
            podName.orEmpty(),
            attempt ?: 1,
        )

        DrainEventMessageType.WAITING_ON_PDB -> stringResource(
            id = R.string.node_drain_event_waiting_pdb,
            namespace.orEmpty(),
            podName.orEmpty(),
            attempt ?: 1,
            ((retryDelayMillis ?: 0L) / 1000L).toInt(),
        )

        DrainEventMessageType.POD_EVICTED -> stringResource(
            id = R.string.node_drain_event_pod_evicted,
            namespace.orEmpty(),
            podName.orEmpty(),
        )

        DrainEventMessageType.COMPLETED_SUCCESS -> stringResource(id = R.string.node_drain_event_completed_success)
        DrainEventMessageType.COMPLETED_NOT_FOUND -> stringResource(id = R.string.node_drain_event_completed_not_found)
        DrainEventMessageType.COMPLETED_FORBIDDEN -> stringResource(id = R.string.node_drain_event_completed_forbidden)
        DrainEventMessageType.COMPLETED_CONFLICT -> stringResource(id = R.string.node_drain_event_completed_conflict)
        DrainEventMessageType.COMPLETED_FAILURE -> stringResource(id = R.string.node_drain_event_completed_failure)
    }
}
