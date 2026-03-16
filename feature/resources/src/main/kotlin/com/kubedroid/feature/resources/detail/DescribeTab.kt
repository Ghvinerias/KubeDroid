package com.kubedroid.feature.resources.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.kubedroid.core.network.resources.ResourceDetail
import com.kubedroid.feature.resources.R

@Composable
fun DescribeTab(
    detail: ResourceDetail,
    modifier: Modifier = Modifier,
) {
    val contentPadding = dimensionResource(id = R.dimen.resource_detail_tab_content_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.resource_detail_content_spacing)
    val rowSpacing = dimensionResource(id = R.dimen.resource_detail_describe_row_spacing)

    val yamlLineCount = if (detail.yaml.isBlank()) 0 else detail.yaml.lines().size
    val yamlCharCount = detail.yaml.length

    Column(
        modifier = modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        DescribeRow(
            label = stringResource(id = R.string.describe_label_kind),
            value = detail.kind,
        )
        DescribeRow(
            label = stringResource(id = R.string.describe_label_name),
            value = detail.name,
        )
        DescribeRow(
            label = stringResource(id = R.string.describe_label_namespace),
            value = detail.namespace ?: stringResource(id = R.string.describe_namespace_cluster_scoped),
        )
        DescribeRow(
            label = stringResource(id = R.string.describe_label_api_version),
            value = detail.apiVersion,
        )
        DescribeRow(
            label = stringResource(id = R.string.describe_label_resource_version),
            value = detail.resourceVersion ?: stringResource(id = R.string.describe_value_unknown),
        )
        DescribeRow(
            label = stringResource(id = R.string.describe_label_yaml_size),
            value = stringResource(
                id = R.string.describe_yaml_size_value,
                yamlLineCount,
                yamlCharCount,
            ),
        )

        Text(
            text = stringResource(id = R.string.describe_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = rowSpacing),
        )
    }
}

@Composable
private fun DescribeRow(
    label: String,
    value: String,
) {
    val rowSpacing = dimensionResource(id = R.dimen.resource_detail_describe_row_spacing)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f),
        )
    }
}
