package com.kubedroid.feature.resources.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.kubedroid.core.network.resources.KubeEvent
import com.kubedroid.feature.resources.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun EventsTab(
    events: List<KubeEvent>,
    modifier: Modifier = Modifier,
) {
    val contentPadding = dimensionResource(id = R.dimen.resource_detail_tab_content_padding)
    val eventSpacing = dimensionResource(id = R.dimen.resource_detail_event_spacing)

    if (events.isEmpty()) {
        Text(
            text = stringResource(id = R.string.events_empty),
            modifier = modifier.padding(contentPadding),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(eventSpacing),
    ) {
        items(events) { event ->
            EventCard(
                event = event,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = contentPadding),
            )
        }
    }
}

@Composable
private fun EventCard(
    event: KubeEvent,
    modifier: Modifier = Modifier,
) {
    val cardPadding = dimensionResource(id = R.dimen.resource_detail_event_card_padding)
    val rowSpacing = dimensionResource(id = R.dimen.resource_detail_event_row_spacing)

    val reason = event.reason ?: stringResource(id = R.string.events_reason_unknown)
    val type = event.type ?: stringResource(id = R.string.events_type_unknown)
    val source = event.source ?: stringResource(id = R.string.events_source_unknown)
    val count = event.count ?: 1

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Text(
                text = stringResource(id = R.string.events_title, type, reason),
                style = MaterialTheme.typography.titleSmall,
                color = if (type.equals("warning", ignoreCase = true)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )

            Text(
                text = event.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = stringResource(id = R.string.events_source, source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(id = R.string.events_count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    id = R.string.events_first_seen,
                    formatTime(event.firstTimestampEpochMillis),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    id = R.string.events_last_seen,
                    formatTime(event.lastTimestampEpochMillis),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun formatTime(epochMillis: Long?): String {
    if (epochMillis == null) {
        return stringResource(id = R.string.events_time_unknown)
    }

    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
    }.getOrElse {
        stringResource(id = R.string.events_time_unknown)
    }
}
