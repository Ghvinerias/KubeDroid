package com.kubedroid.feature.helm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kubedroid.feature.helm.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelmDetailScreen(
    releaseName: String,
    selectedTab: HelmDetailTab,
    infoItems: List<Pair<String, String>>,
    revisions: List<HelmRevisionUiModel>,
    manifest: String,
    onTabSelected: (HelmDetailTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenPadding = dimensionResource(id = R.dimen.helm_screen_padding)
    val contentSpacing = dimensionResource(id = R.dimen.helm_content_spacing)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = releaseName) },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = screenPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                HelmDetailTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        text = { Text(text = stringResource(id = tab.titleResId)) },
                    )
                }
            }

            when (selectedTab) {
                HelmDetailTab.INFO -> {
                    if (infoItems.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.helm_detail_info_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(infoItems, key = { it.first }) { item ->
                                HelmInfoRow(label = item.first, value = item.second)
                                HorizontalDivider()
                            }
                        }
                    }
                }

                HelmDetailTab.HISTORY -> {
                    if (revisions.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.helm_detail_history_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(revisions, key = { it.revision }) { revision ->
                                HelmRevisionRow(
                                    revision = revision,
                                    selected = false,
                                    onClick = {},
                                    showSelectionControl = false,
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }

                HelmDetailTab.MANIFEST -> {
                    Text(
                        text = if (manifest.isBlank()) {
                            stringResource(id = R.string.helm_detail_manifest_empty)
                        } else {
                            manifest
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun HelmInfoRow(
    label: String,
    value: String,
) {
    val rowPadding = dimensionResource(id = R.dimen.helm_list_item_padding)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = rowPadding),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
