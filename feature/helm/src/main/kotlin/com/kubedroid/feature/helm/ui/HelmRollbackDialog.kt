package com.kubedroid.feature.helm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.kubedroid.feature.helm.R

@Composable
fun HelmRollbackDialog(
    releaseName: String,
    revisions: List<HelmRevisionUiModel>,
    onConfirmRollback: (revision: Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val contentSpacing = dimensionResource(id = R.dimen.helm_content_spacing)
    val maxListHeight = dimensionResource(id = R.dimen.helm_rollback_list_max_height)
    var selectedRevision by remember(revisions) {
        mutableIntStateOf(revisions.firstOrNull()?.revision ?: -1)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(id = R.string.helm_rollback_dialog_title, releaseName))
        },
        text = {
            if (revisions.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.helm_rollback_dialog_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(contentSpacing)) {
                    Text(
                        text = stringResource(id = R.string.helm_rollback_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = maxListHeight)) {
                        items(items = revisions, key = { it.revision }) { revision ->
                            HelmRevisionRow(
                                revision = revision,
                                selected = selectedRevision == revision.revision,
                                onClick = { selectedRevision = revision.revision },
                                showSelectionControl = true,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirmRollback(selectedRevision) },
                enabled = selectedRevision != -1,
            ) {
                Text(text = stringResource(id = R.string.helm_rollback_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(id = R.string.helm_rollback_dialog_cancel))
            }
        },
    )
}
