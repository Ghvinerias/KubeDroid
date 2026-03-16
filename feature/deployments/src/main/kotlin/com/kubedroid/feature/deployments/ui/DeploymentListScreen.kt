package com.kubedroid.feature.deployments.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import com.kubedroid.core.ui.namespace.NamespaceSelector
import com.kubedroid.feature.deployments.R
import kotlinx.coroutines.launch

@Composable
fun DeploymentListRoute(
    viewModel: DeploymentListViewModel,
    onDeploymentClick: (namespace: String, deploymentName: String) -> Unit,
    useTwoPane: Boolean = false,
    detailPane: (@Composable () -> Unit)? = null,
    hingePadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedNamespace by viewModel.selectedNamespace.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.onIntent(DeploymentListIntent.LoadNamespaces)
    }

    DeploymentListScreen(
        state = uiState,
        selectedNamespace = selectedNamespace,
        namespaces = uiState.availableNamespaces,
        onNamespaceSelected = viewModel::selectNamespace,
        onRetryClick = { viewModel.onIntent(DeploymentListIntent.Retry) },
        onDeploymentClick = onDeploymentClick,
        useTwoPane = useTwoPane,
        detailPane = detailPane,
        hingePadding = hingePadding,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun DeploymentListScreen(
    state: DeploymentListUiState,
    selectedNamespace: String,
    namespaces: List<String>,
    onNamespaceSelected: (String) -> Unit,
    onRetryClick: () -> Unit,
    onDeploymentClick: (namespace: String, deploymentName: String) -> Unit,
    useTwoPane: Boolean = false,
    detailPane: (@Composable () -> Unit)? = null,
    hingePadding: Dp = 0.dp,
) {
    val screenPadding = dimensionResource(id = R.dimen.deployment_screen_padding)
    val itemPadding = dimensionResource(id = R.dimen.deployment_list_item_padding)
    val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<Unit>()
    val scope = rememberCoroutineScope()
    var isNamespaceSheetVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.deployment_list_title)) },
                actions = {
                    IconButton(onClick = onRetryClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.deployment_list_refresh_cd),
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
                        DeploymentListPane(
                            state = state,
                            selectedNamespace = selectedNamespace,
                            namespaces = namespaces,
                            onNamespaceSelected = onNamespaceSelected,
                            isNamespaceSheetVisible = isNamespaceSheetVisible,
                            onNamespaceSheetVisibilityChange = { isNamespaceSheetVisible = it },
                            screenPadding = screenPadding,
                            itemPadding = itemPadding,
                            onDeploymentClick = { namespace, name ->
                                onDeploymentClick(namespace, name)
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
            DeploymentListPane(
                state = state,
                selectedNamespace = selectedNamespace,
                namespaces = namespaces,
                onNamespaceSelected = onNamespaceSelected,
                isNamespaceSheetVisible = isNamespaceSheetVisible,
                onNamespaceSheetVisibilityChange = { isNamespaceSheetVisible = it },
                screenPadding = screenPadding,
                itemPadding = itemPadding,
                onDeploymentClick = onDeploymentClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        }
    }
}

@Composable
private fun DeploymentListPane(
    state: DeploymentListUiState,
    selectedNamespace: String,
    namespaces: List<String>,
    onNamespaceSelected: (String) -> Unit,
    isNamespaceSheetVisible: Boolean,
    onNamespaceSheetVisibilityChange: (Boolean) -> Unit,
    screenPadding: androidx.compose.ui.unit.Dp,
    itemPadding: androidx.compose.ui.unit.Dp,
    onDeploymentClick: (namespace: String, deploymentName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = screenPadding),
        verticalArrangement = Arrangement.spacedBy(itemPadding),
    ) {
        NamespaceSelector(
            namespaces = namespaces,
            selectedNamespace = selectedNamespace.ifBlank { null },
            onNamespaceSelected = { namespace ->
                namespace?.let(onNamespaceSelected)
            },
            isSheetVisible = isNamespaceSheetVisible,
            onSheetVisibilityChange = onNamespaceSheetVisibilityChange,
            allowAllNamespaces = true,
        )

        if (state.isDataFromCache) {
            StaleDataBanner(lastFetchedAtEpochMillis = state.lastUpdatedAtEpochMillis)
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
        when (val listState = state.listState) {
            DeploymentListContentState.Loading -> {
                ListShimmerPlaceholder(modifier = Modifier.fillMaxSize())
            }

            DeploymentListContentState.Empty -> {
                Text(text = stringResource(id = R.string.deployment_list_empty))
            }

            is DeploymentListContentState.Error -> {
                Text(
                    text = listState.cause?.localizedMessage
                        ?: stringResource(id = R.string.deployment_list_error),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is DeploymentListContentState.Success -> {
                val showNamespace = selectedNamespace.isAllNamespacesSelection()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = listState.items, key = { item -> item.stableKey }) { item ->
                        DeploymentListRow(
                            item = item,
                            showNamespace = showNamespace,
                            onClick = {
                                onDeploymentClick(item.namespace, item.name)
                            },
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
private fun DeploymentListRow(
    item: DeploymentListItem,
    showNamespace: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    id = R.string.deployment_list_replica_summary,
                    item.readyReplicas,
                    item.desiredReplicas,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showNamespace) {
                Text(
                    text = item.namespace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item.observedGeneration?.let { generation ->
            Text(
                text = stringResource(id = R.string.deployment_list_observed_generation, generation),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
