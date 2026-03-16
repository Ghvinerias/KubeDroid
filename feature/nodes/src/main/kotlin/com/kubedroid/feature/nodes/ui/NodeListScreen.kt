package com.kubedroid.feature.nodes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.feature.metrics.ui.NodeMetricsBar
import com.kubedroid.feature.nodes.R
import kotlinx.coroutines.launch

@Composable
fun NodeListRoute(
    viewModel: NodeListViewModel,
    onNodeClick: (nodeName: String) -> Unit,
    useTwoPane: Boolean = false,
    detailPane: (@Composable () -> Unit)? = null,
    hingePadding: Dp = 0.dp,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.onIntent(NodeListIntent.Load)
    }

    NodeListScreen(
        state = state,
        onRetryClick = { viewModel.onIntent(NodeListIntent.Retry) },
        onNodeClick = onNodeClick,
        useTwoPane = useTwoPane,
        detailPane = detailPane,
        hingePadding = hingePadding,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun NodeListScreen(
    state: NodeListUiState,
    onRetryClick: () -> Unit,
    onNodeClick: (nodeName: String) -> Unit,
    useTwoPane: Boolean = false,
    detailPane: (@Composable () -> Unit)? = null,
    hingePadding: Dp = 0.dp,
) {
    val screenPadding = dimensionResource(id = R.dimen.nodes_screen_padding)
    val itemPadding = dimensionResource(id = R.dimen.nodes_item_padding)
    val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<Unit>()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.nodes_list_title)) },
                actions = {
                    IconButton(onClick = onRetryClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.nodes_list_refresh_cd),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        if (useTwoPane && detailPane != null) {
            ListDetailPaneScaffold(
                directive = scaffoldNavigator.scaffoldDirective,
                value = scaffoldNavigator.scaffoldValue,
                listPane = {
                    AnimatedPane(modifier = Modifier.padding(end = hingePadding)) {
                        NodeListPane(
                            state = state,
                            screenPadding = screenPadding,
                            itemPadding = itemPadding,
                            onNodeClick = { nodeName ->
                                onNodeClick(nodeName)
                                scope.launch {
                                    scaffoldNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                                }
                            },
                        )
                    }
                },
                detailPane = {
                    AnimatedPane(modifier = Modifier.padding(start = hingePadding)) { detailPane() }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        } else {
            NodeListPane(
                state = state,
                screenPadding = screenPadding,
                itemPadding = itemPadding,
                onNodeClick = onNodeClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        }
    }
}

@Composable
private fun NodeListPane(
    state: NodeListUiState,
    screenPadding: androidx.compose.ui.unit.Dp,
    itemPadding: androidx.compose.ui.unit.Dp,
    onNodeClick: (nodeName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = screenPadding),
        verticalArrangement = Arrangement.spacedBy(itemPadding),
    ) {
        if (state.isDataFromCache) {
            StaleDataBanner(lastFetchedAtEpochMillis = state.lastUpdatedAtEpochMillis)
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
        when (val listState = state.listState) {
            NodeListContentState.Loading -> ListShimmerPlaceholder(modifier = Modifier.fillMaxSize())
            NodeListContentState.Empty -> {
                Text(text = stringResource(id = R.string.nodes_list_empty))
            }

            is NodeListContentState.Error -> {
                Text(
                    text = listState.cause?.localizedMessage
                        ?: stringResource(id = R.string.nodes_list_error),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is NodeListContentState.Success -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = listState.items, key = { item -> item.stableKey }) { item ->
                        NodeListRow(
                            item = item,
                            onClick = { onNodeClick(item.name) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = itemPadding),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun NodeListRow(
    item: NodeListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sectionSpacing = dimensionResource(id = R.dimen.nodes_section_spacing)

    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        id = R.string.nodes_list_roles,
                        if (item.roles.isEmpty()) {
                            stringResource(id = R.string.nodes_list_role_worker)
                        } else {
                            item.roles.joinToString()
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val statusLabel = when {
                item.unschedulable -> stringResource(id = R.string.nodes_status_unschedulable)
                item.ready -> stringResource(id = R.string.nodes_status_ready)
                else -> stringResource(id = R.string.nodes_status_not_ready)
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (item.ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        NodeMetricsBar(
            cpuPercent = item.cpuPercent,
            memoryPercent = item.memoryPercent,
        )
    }
}
