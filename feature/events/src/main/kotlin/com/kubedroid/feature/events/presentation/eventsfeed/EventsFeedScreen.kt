package com.kubedroid.feature.events.presentation.eventsfeed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.feature.events.R
import com.kubedroid.feature.events.domain.model.ClusterEvent
import com.kubedroid.feature.events.domain.model.ClusterEventType
import java.time.Duration
import java.time.Instant

@Composable
fun EventsFeedRoute(
    viewModel: EventsFeedViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    EventsFeedScreen(
        state = state,
        onRetry = { viewModel.onIntent(EventsFeedIntent.Retry) },
        onNamespaceSelected = { namespace -> viewModel.onIntent(EventsFeedIntent.SelectNamespace(namespace)) },
        onTypeSelected = { filter -> viewModel.onIntent(EventsFeedIntent.SelectType(filter)) },
        onSearchQueryChange = { query -> viewModel.onIntent(EventsFeedIntent.UpdateSearchQuery(query)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsFeedScreen(
    state: EventsFeedUiState,
    onRetry: () -> Unit,
    onNamespaceSelected: (String?) -> Unit,
    onTypeSelected: (EventTypeFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
) {
    val horizontalPadding = dimensionResource(id = R.dimen.events_screen_horizontal_padding)
    val verticalPadding = dimensionResource(id = R.dimen.events_screen_vertical_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.events_screen_section_spacing)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.events_feed_title)) },
                actions = {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.events_refresh_cd),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            EventFilterBar(
                selectedNamespace = state.selectedNamespace,
                namespaces = state.availableNamespaces,
                typeFilter = state.typeFilter,
                searchQuery = state.searchQuery,
                onNamespaceSelected = onNamespaceSelected,
                onTypeSelected = onTypeSelected,
                onSearchQueryChange = onSearchQueryChange,
            )

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.error,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                state.events.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = stringResource(id = R.string.events_empty))
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = state.events,
                            key = { event -> event.uid },
                            contentType = { "event_row" },
                        ) { event ->
                            EventRow(event = event)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFilterBar(
    selectedNamespace: String?,
    namespaces: List<String>,
    typeFilter: EventTypeFilter,
    searchQuery: String,
    onNamespaceSelected: (String?) -> Unit,
    onTypeSelected: (EventTypeFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
) {
    val sectionSpacing = dimensionResource(id = R.dimen.events_screen_section_spacing)
    val chipSpacing = dimensionResource(id = R.dimen.events_filter_chip_spacing)

    Column(
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(chipSpacing),
        ) {
            item(key = "namespace-all") {
                FilterChip(
                    selected = selectedNamespace == null,
                    onClick = { onNamespaceSelected(null) },
                    label = {
                        Text(
                            text = stringResource(id = R.string.events_filter_all_namespaces),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }

            items(
                items = namespaces,
                key = { namespace -> namespace },
            ) { namespace ->
                FilterChip(
                    selected = selectedNamespace == namespace,
                    onClick = { onNamespaceSelected(namespace) },
                    label = {
                        Text(
                            text = namespace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            EventTypeFilter.entries.forEachIndexed { index, filter ->
                val label = when (filter) {
                    EventTypeFilter.All -> stringResource(id = R.string.events_filter_type_all)
                    EventTypeFilter.Warning -> stringResource(id = R.string.events_filter_type_warning)
                    EventTypeFilter.Normal -> stringResource(id = R.string.events_filter_type_normal)
                }
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = EventTypeFilter.entries.size,
                    ),
                    selected = typeFilter == filter,
                    onClick = { onTypeSelected(filter) },
                ) {
                    Text(text = label)
                }
            }
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                )
            },
            label = { Text(text = stringResource(id = R.string.events_filter_search_label)) },
            placeholder = { Text(text = stringResource(id = R.string.events_filter_search_placeholder)) },
        )
    }
}

@Composable
fun EventRow(
    event: ClusterEvent,
    modifier: Modifier = Modifier,
) {
    val rowPadding = dimensionResource(id = R.dimen.events_row_padding)
    val rowSpacing = dimensionResource(id = R.dimen.events_row_spacing)
    val iconSize = dimensionResource(id = R.dimen.events_row_icon_size)

    val isWarning = event.type == ClusterEventType.Warning
    val backgroundColor = if (isWarning) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val icon = if (isWarning) Icons.Default.ErrorOutline else Icons.Default.Event
    val iconTint = if (isWarning) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = rowPadding)
            .padding(rowPadding),
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
            modifier = Modifier.padding(rowPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(rowSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier.size(iconSize),
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                    )
                    Text(
                        text = event.involvedObjectName.ifBlank { event.involvedObjectKind },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = event.lastTimestamp.asRelativeAge(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = event.reason,
                style = MaterialTheme.typography.labelLarge,
                color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = event.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Instant.asRelativeAge(now: Instant = Instant.now()): String {
    val duration = Duration.between(this, now).coerceAtLeast(Duration.ZERO)
    val seconds = duration.seconds
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        else -> "${seconds / 86_400}d"
    }
}
