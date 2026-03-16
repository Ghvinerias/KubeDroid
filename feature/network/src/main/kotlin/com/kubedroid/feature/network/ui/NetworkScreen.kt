package com.kubedroid.feature.network.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.feature.network.R

@Composable
fun NetworkRoute(
    viewModel: NetworkViewModel,
    onIngressClick: (namespace: String, ingressName: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current

    NetworkScreen(
        state = state,
        onTabSelected = viewModel::onTabSelected,
        onRefresh = viewModel::refresh,
        onNamespaceChange = viewModel::onNamespaceChange,
        onIngressClick = onIngressClick,
        onCopyUrl = { url -> copyToClipboard(clipboardManager, url) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    state: NetworkUiState,
    onTabSelected: (NetworkTab) -> Unit,
    onRefresh: () -> Unit,
    onNamespaceChange: (String) -> Unit,
    onIngressClick: (namespace: String, ingressName: String) -> Unit,
    onCopyUrl: (String) -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.network_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.network_section_spacing)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.network_title)) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.network_refresh_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = screenPadding),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            TabRow(
                selectedTabIndex = when (state.selectedTab) {
                    NetworkTab.INGRESSES -> 0
                    NetworkTab.NETWORK_POLICIES -> 1
                },
            ) {
                Tab(
                    selected = state.selectedTab == NetworkTab.INGRESSES,
                    onClick = { onTabSelected(NetworkTab.INGRESSES) },
                    text = { Text(text = stringResource(id = R.string.network_tab_ingresses)) },
                )
                Tab(
                    selected = state.selectedTab == NetworkTab.NETWORK_POLICIES,
                    onClick = { onTabSelected(NetworkTab.NETWORK_POLICIES) },
                    text = { Text(text = stringResource(id = R.string.network_tab_policies)) },
                )
            }

            NamespaceSelector(
                namespace = state.namespace,
                options = buildNamespaceOptions(
                    selectedNamespace = state.namespace,
                    namespaces = state.namespaces,
                ),
                label = stringResource(id = R.string.network_namespace_label),
                onNamespaceSelected = onNamespaceChange,
            )

            when (state.selectedTab) {
                NetworkTab.INGRESSES -> IngressTabContent(
                    listState = state.ingressState,
                    items = state.ingresses,
                    onIngressClick = onIngressClick,
                    onCopyUrl = onCopyUrl,
                )

                NetworkTab.NETWORK_POLICIES -> NetworkPolicyTabContent(
                    listState = state.networkPolicyState,
                    items = state.networkPolicies,
                )
            }
        }
    }
}

private fun buildNamespaceOptions(
    selectedNamespace: String,
    namespaces: List<String>,
): List<String> {
    return buildSet {
        add(DEFAULT_NAMESPACE)
        add(selectedNamespace)
        namespaces.forEach { add(it) }
    }
        .filter { it.isNotBlank() }
        .sorted()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NamespaceSelector(
    namespace: String,
    options: List<String>,
    label: String,
    onNamespaceSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = namespace,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(text = label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = option) },
                    onClick = {
                        expanded = false
                        onNamespaceSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun IngressTabContent(
    listState: NetworkListState,
    items: List<IngressListItemUi>,
    onIngressClick: (namespace: String, ingressName: String) -> Unit,
    onCopyUrl: (String) -> Unit,
) {
    when (listState) {
        NetworkListState.Loading -> CenteredLoading()
        NetworkListState.Empty -> CenteredMessage(text = stringResource(id = R.string.network_state_empty_ingresses))
        is NetworkListState.Error -> CenteredError(
            message = listState.cause?.localizedMessage
                ?: stringResource(id = R.string.network_state_error),
        )

        NetworkListState.Success -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = items, key = { "${it.namespace}:${it.name}" }) { item ->
                    IngressListItem(
                        item = item,
                        onClick = { onIngressClick(item.namespace, item.name) },
                        onCopyUrl = onCopyUrl,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun NetworkPolicyTabContent(
    listState: NetworkListState,
    items: List<NetworkPolicyListItemUi>,
) {
    when (listState) {
        NetworkListState.Loading -> CenteredLoading()
        NetworkListState.Empty -> CenteredMessage(text = stringResource(id = R.string.network_state_empty_policies))
        is NetworkListState.Error -> CenteredError(
            message = listState.cause?.localizedMessage
                ?: stringResource(id = R.string.network_state_error),
        )

        NetworkListState.Success -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = items, key = { "${it.namespace}:${it.name}" }) { item ->
                    NetworkPolicyListItem(
                        item = item,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun IngressListItem(
    item: IngressListItemUi,
    onClick: () -> Unit,
    onCopyUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemPadding = dimensionResource(id = R.dimen.network_item_padding)
    val rowSpacing = dimensionResource(id = R.dimen.network_row_spacing)
    Card(
        onClick = onClick,
        modifier = modifier.padding(vertical = rowSpacing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    color = if (item.hasTlsForPrimaryHost) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = if (item.hasTlsForPrimaryHost) {
                            stringResource(id = R.string.network_ingress_tls)
                        } else {
                            stringResource(id = R.string.network_ingress_no_tls)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.hasTlsForPrimaryHost) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = rowSpacing, vertical = rowSpacing / 2),
                    )
                }
            }
            Text(
                text = stringResource(
                    id = R.string.network_ingress_host,
                    item.primaryHost ?: stringResource(id = R.string.network_ingress_host_all),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.network_ingress_paths, item.pathCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { item.copyUrl?.let(onCopyUrl) },
                enabled = item.copyUrl != null,
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(id = R.string.network_ingress_copy_url_cd),
                )
                Text(text = stringResource(id = R.string.network_ingress_copy_url))
            }
        }
    }
}

@Composable
fun NetworkPolicyListItem(
    item: NetworkPolicyListItemUi,
    modifier: Modifier = Modifier,
) {
    val itemPadding = dimensionResource(id = R.dimen.network_item_padding)
    val rowSpacing = dimensionResource(id = R.dimen.network_row_spacing)
    Card(
        modifier = modifier.padding(vertical = rowSpacing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    color = if (item.allowsTraffic) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = if (item.allowsTraffic) {
                            stringResource(id = R.string.network_policy_allow)
                        } else {
                            stringResource(id = R.string.network_policy_deny)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.allowsTraffic) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(horizontal = rowSpacing, vertical = rowSpacing / 2),
                    )
                }
            }
            Text(
                text = stringResource(
                    id = R.string.network_policy_selector,
                    item.podSelectorSummary.ifBlank {
                        stringResource(id = R.string.network_policy_selector_all)
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun IngressDetailRoute(
    namespace: String,
    ingressName: String,
    viewModel: NetworkViewModel,
    onBackClick: () -> Unit,
) {
    val state by viewModel.ingressDetailState.collectAsStateWithLifecycle()

    LaunchedEffect(namespace, ingressName) {
        viewModel.openIngressDetail(namespace = namespace, ingressName = ingressName)
    }

    IngressDetailScreen(
        state = state,
        onBackClick = onBackClick,
        onRefresh = viewModel::refreshIngressDetail,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngressDetailScreen(
    state: IngressDetailState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.network_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.network_section_spacing)
    val indentSize = dimensionResource(id = R.dimen.network_tree_indent)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            id = R.string.network_detail_title,
                            state.ingressName,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.network_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.network_refresh_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val content = state.contentState) {
            IngressDetailContentState.Loading -> CenteredLoading(modifier = Modifier.padding(innerPadding))
            is IngressDetailContentState.Error -> CenteredError(
                message = content.cause?.localizedMessage ?: stringResource(id = R.string.network_state_error),
                modifier = Modifier.padding(innerPadding),
            )

            is IngressDetailContentState.Success -> {
                if (content.detail.routes.isEmpty()) {
                    CenteredMessage(
                        text = stringResource(id = R.string.network_detail_empty),
                        modifier = Modifier.padding(innerPadding),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = screenPadding),
                        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                    ) {
                        item {
                            Text(
                                text = stringResource(id = R.string.network_detail_routes_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        items(content.detail.routes) { route ->
                            Column(
                                verticalArrangement = Arrangement.spacedBy(sectionSpacing / 2),
                            ) {
                                Text(
                                    text = stringResource(
                                        id = R.string.network_detail_host_value,
                                        stringResource(id = R.string.network_detail_host_label),
                                        route.host ?: stringResource(id = R.string.network_ingress_host_all),
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                route.paths.forEach { pathRoute ->
                                    Column(
                                        modifier = Modifier.padding(start = indentSize),
                                        verticalArrangement = Arrangement.spacedBy(sectionSpacing / 3),
                                    ) {
                                        Text(
                                            text = stringResource(
                                                id = R.string.network_detail_path_value,
                                                stringResource(id = R.string.network_detail_path_label),
                                                pathRoute.path,
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = stringResource(
                                                id = R.string.network_detail_service_value,
                                                stringResource(id = R.string.network_detail_service_label),
                                                pathRoute.serviceName.ifBlank {
                                                    stringResource(id = R.string.network_detail_service_unknown)
                                                },
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(start = indentSize),
                                        )
                                        Text(
                                            text = stringResource(
                                                id = R.string.network_detail_port_value,
                                                stringResource(id = R.string.network_detail_port_label),
                                                pathRoute.servicePort
                                                    ?: stringResource(id = R.string.network_detail_port_unknown),
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(start = indentSize),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(
    clipboardManager: ClipboardManager,
    value: String,
) {
    clipboardManager.setText(AnnotatedString(value))
}

@Composable
private fun CenteredLoading(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredError(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
