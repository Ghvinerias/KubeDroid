package com.kubedroid.feature.deployments.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.kubedroid.core.network.deployments.RolloutRevision
import com.kubedroid.feature.deployments.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun RolloutHistoryScreen(
    state: RolloutHistoryUiState,
    onRetryClick: () -> Unit,
    onRollbackClick: (revision: Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentPadding = dimensionResource(id = R.dimen.deployment_content_spacing)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            RolloutHistoryUiState.Loading -> {
                CircularProgressIndicator()
            }

            RolloutHistoryUiState.Empty -> {
                Text(text = stringResource(id = R.string.rollout_history_empty))
            }

            is RolloutHistoryUiState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(contentPadding),
                ) {
                    Text(
                        text = state.cause?.localizedMessage
                            ?: stringResource(id = R.string.rollout_history_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onRetryClick) {
                        Text(text = stringResource(id = R.string.rollout_history_retry))
                    }
                }
            }

            is RolloutHistoryUiState.Success -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(contentPadding),
                ) {
                    items(state.revisions, key = { revision -> revision.revision }) { revision ->
                        RolloutHistoryCard(
                            revision = revision,
                            onRollbackClick = onRollbackClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RolloutHistoryCard(
    revision: RolloutRevision,
    onRollbackClick: (revision: Long?) -> Unit,
) {
    val cardPadding = dimensionResource(id = R.dimen.deployment_list_item_padding)
    val contentSpacing = dimensionResource(id = R.dimen.deployment_content_spacing)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            Text(
                text = stringResource(id = R.string.rollout_history_revision, revision.revision),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    id = R.string.rollout_history_change_cause,
                    revision.changeCause ?: stringResource(id = R.string.rollout_history_change_cause_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    id = R.string.rollout_history_created_at,
                    revision.createdAtEpochMillis?.toUserDateTime()
                        ?: stringResource(id = R.string.rollout_history_created_at_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onRollbackClick(revision.revision) }) {
                    Text(text = stringResource(id = R.string.rollout_history_rollback))
                }
            }
        }
    }
}

private fun Long.toUserDateTime(): String {
    val instant = Instant.ofEpochMilli(this)
    val formatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}
