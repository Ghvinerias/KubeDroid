package com.kubedroid.feature.metrics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kubedroid.feature.metrics.R
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import kotlin.math.roundToInt

@Composable
fun MetricsGraphScreen(
    cpuSeries: List<MetricSeriesPoint>,
    memorySeries: List<MetricSeriesPoint>,
    isLoading: Boolean = false,
    isMetricsServerAvailable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val screenPadding = dimensionResource(id = R.dimen.metrics_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.metrics_section_spacing)

    if (isLoading) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(id = R.string.metrics_graph_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (!isMetricsServerAvailable) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(screenPadding),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            Text(
                text = stringResource(id = R.string.metrics_server_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        item {
            MetricLineChartCard(
                title = stringResource(id = R.string.metrics_graph_cpu_title),
                emptyText = stringResource(id = R.string.metrics_graph_empty),
                values = cpuSeries.map { it.value },
            )
        }
        item {
            MetricLineChartCard(
                title = stringResource(id = R.string.metrics_graph_memory_title),
                emptyText = stringResource(id = R.string.metrics_graph_empty),
                values = memorySeries.map { it.value },
            )
        }
        item {
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.height(screenPadding),
            )
        }
    }
}

@Composable
private fun MetricLineChartCard(
    title: String,
    values: List<Float>,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    val contentPadding = dimensionResource(id = R.dimen.metrics_card_padding)
    val chartHeight = dimensionResource(id = R.dimen.metrics_chart_height)
    val sectionSpacing = dimensionResource(id = R.dimen.metrics_section_spacing)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )

            if (values.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Chart(
                    chart = lineChart(),
                    model = entryModelOf(*values.toTypedArray()),
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeight),
                )
            }
        }
    }
}

@Composable
fun PodMetricsSummaryRow(
    cpuMillicoresSeries: List<Long>,
    memoryBytesSeries: List<Long>,
    modifier: Modifier = Modifier,
) {
    val sectionSpacing = dimensionResource(id = R.dimen.metrics_compact_spacing)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        SparklineMetricChip(
            modifier = Modifier.weight(1f),
            label = stringResource(id = R.string.metrics_chip_cpu),
            value = cpuMillicoresSeries.lastOrNull()?.let { milli ->
                stringResource(id = R.string.metrics_cpu_millicores_value, milli)
            } ?: stringResource(id = R.string.metrics_value_unavailable),
            values = cpuMillicoresSeries.map { it.toFloat() },
        )
        SparklineMetricChip(
            modifier = Modifier.weight(1f),
            label = stringResource(id = R.string.metrics_chip_memory),
            value = memoryBytesSeries.lastOrNull()?.let { bytes ->
                stringResource(id = R.string.metrics_memory_mebibytes_value, bytes.toMebibytesRounded())
            } ?: stringResource(id = R.string.metrics_value_unavailable),
            values = memoryBytesSeries.map { it.toFloat() },
        )
    }
}

@Composable
private fun SparklineMetricChip(
    label: String,
    value: String,
    values: List<Float>,
    modifier: Modifier = Modifier,
) {
    val chipPadding = dimensionResource(id = R.dimen.metrics_compact_chip_padding)
    val rowSpacing = dimensionResource(id = R.dimen.metrics_compact_spacing)
    val sparklineWidth = dimensionResource(id = R.dimen.metrics_sparkline_width)
    val sparklineHeight = dimensionResource(id = R.dimen.metrics_sparkline_height)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(chipPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            if (values.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.metrics_compact_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Chart(
                    chart = lineChart(),
                    model = entryModelOf(*values.toTypedArray()),
                    modifier = Modifier.size(width = sparklineWidth, height = sparklineHeight),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun NodeMetricsBar(
    cpuPercent: Float?,
    memoryPercent: Float?,
    modifier: Modifier = Modifier,
) {
    val sectionSpacing = dimensionResource(id = R.dimen.metrics_section_spacing)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        MetricProgressRow(
            label = stringResource(id = R.string.metrics_chip_cpu),
            percent = cpuPercent,
        )
        MetricProgressRow(
            label = stringResource(id = R.string.metrics_chip_memory),
            percent = memoryPercent,
        )
    }
}

@Composable
private fun MetricProgressRow(
    label: String,
    percent: Float?,
) {
    val barSpacing = dimensionResource(id = R.dimen.metrics_bar_spacing)

    Column(verticalArrangement = Arrangement.spacedBy(barSpacing)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (percent == null) {
                    stringResource(id = R.string.metrics_value_unavailable)
                } else {
                    stringResource(id = R.string.metrics_percent_value, percent)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        LinearProgressIndicator(
            progress = { ((percent ?: 0f) / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ResourceQuotaCard(
    title: String,
    quotas: List<ResourceQuotaUsageItem>,
    modifier: Modifier = Modifier,
) {
    val contentPadding = dimensionResource(id = R.dimen.metrics_card_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.metrics_section_spacing)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )

            if (quotas.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.metrics_graph_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                quotas.forEach { quota ->
                    val progress = if (quota.hard <= 0f) 0f else (quota.used / quota.hard).coerceIn(0f, 1f)

                    Column(verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = quota.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(
                                    id = R.string.metrics_quota_usage_value,
                                    quota.usedDisplay,
                                    quota.hardDisplay,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = sectionSpacing),
                            )
                        }

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun Long.toMebibytesRounded(): Int =
    (this.toDouble() / (1024.0 * 1024.0)).roundToInt()
