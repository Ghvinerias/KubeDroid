package com.kubedroid.feature.nodes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.kubedroid.feature.nodes.R

@Composable
fun NodeActionBar(
    unschedulable: Boolean,
    isActionInProgress: Boolean,
    onCordonClick: () -> Unit,
    onUncordonClick: () -> Unit,
    onDrainClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonSpacing = dimensionResource(id = R.dimen.nodes_action_bar_spacing)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
    ) {
        if (unschedulable) {
            OutlinedButton(
                onClick = onUncordonClick,
                enabled = !isActionInProgress,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(id = R.string.node_action_uncordon))
            }
        } else {
            OutlinedButton(
                onClick = onCordonClick,
                enabled = !isActionInProgress,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(id = R.string.node_action_cordon))
            }
        }

        Button(
            onClick = onDrainClick,
            enabled = !isActionInProgress,
            modifier = Modifier.weight(1f),
        ) {
            Text(text = stringResource(id = R.string.node_action_drain))
        }
    }
}

@Composable
fun NodeActionConfirmationDialog(
    action: NodeActionType,
    nodeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, body) = when (action) {
        NodeActionType.CORDON -> {
            stringResource(id = R.string.node_confirm_cordon_title) to
                stringResource(id = R.string.node_confirm_cordon_body, nodeName)
        }

        NodeActionType.UNCORDON -> {
            stringResource(id = R.string.node_confirm_uncordon_title) to
                stringResource(id = R.string.node_confirm_uncordon_body, nodeName)
        }

        NodeActionType.DRAIN -> {
            stringResource(id = R.string.node_confirm_drain_title) to
                stringResource(id = R.string.node_confirm_drain_body, nodeName)
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(text = body)
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.node_confirm_action_confirm))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.node_confirm_action_cancel))
            }
        },
    )
}
