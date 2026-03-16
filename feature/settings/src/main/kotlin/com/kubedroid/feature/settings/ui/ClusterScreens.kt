package com.kubedroid.feature.settings.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kubedroid.core.network.connection.ClusterConnectionState
import com.kubedroid.feature.settings.ClusterHealth
import com.kubedroid.feature.settings.ClusterSummary
import com.kubedroid.feature.settings.R

enum class AddClusterInputMode {
    KUBECONFIG,
    MANUAL,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onClusterClick: (String) -> Unit,
    onAddClusterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val addFabCd = stringResource(id = R.string.dashboard_add_cluster_fab_cd)
    val pullToRefreshCd = stringResource(id = R.string.dashboard_pull_to_refresh_cd)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.dashboard_title)) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClusterClick,
                modifier = Modifier.semantics { contentDescription = addFabCd },
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                )
            }
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .semantics { contentDescription = pullToRefreshCd },
        ) {
            when (state) {
                DashboardUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                DashboardUiState.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(id = R.string.dashboard_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is DashboardUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.cause?.message
                                ?: stringResource(id = R.string.dashboard_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                is DashboardUiState.Success -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(MaterialTheme.spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                    ) {
                        items(state.summaries, key = { it.contextName }) { summary ->
                            ClusterHealthCard(
                                summary = summary,
                                onClick = { onClusterClick(summary.contextName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterHealthCard(
    summary: ClusterSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clusterCardCd = stringResource(id = R.string.dashboard_cluster_card_cd, summary.contextName)
    val healthLabel = when (summary.health) {
        ClusterHealth.Healthy -> stringResource(id = R.string.dashboard_health_healthy)
        ClusterHealth.Degraded -> stringResource(id = R.string.dashboard_health_degraded)
        ClusterHealth.Unreachable -> stringResource(id = R.string.dashboard_health_unreachable)
    }
    val healthIndicatorCd = stringResource(
        id = R.string.dashboard_health_indicator_cd,
        healthLabel,
    )

    ElevatedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = clusterCardCd },
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = summary.contextName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (summary.warningEventCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = stringResource(
                                id = R.string.dashboard_warning_badge,
                                summary.warningEventCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .padding(0.dp)
                        .semantics {
                            contentDescription = healthIndicatorCd
                        },
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = summary.health.color(),
                        shape = MaterialTheme.shapes.extraLarge,
                        content = {},
                    )
                }
                Text(
                    text = healthLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = summary.health.color(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                Text(
                    text = stringResource(id = R.string.dashboard_pod_count, summary.podCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(id = R.string.dashboard_node_count, summary.nodeCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            MetricBar(
                label = stringResource(id = R.string.dashboard_cpu_usage, summary.cpuUsagePct.toDisplayPercent()),
                progress = summary.cpuUsagePct / 100f,
            )
            MetricBar(
                label = stringResource(id = R.string.dashboard_memory_usage, summary.memoryUsagePct.toDisplayPercent()),
                progress = summary.memoryUsagePct / 100f,
            )
        }
    }
}

@Composable
private fun MetricBar(
    label: String,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun Float.toDisplayPercent(): Int = coerceIn(0f, 100f).toInt()

@Composable
private fun ClusterHealth.color(): Color {
    return when (this) {
        ClusterHealth.Healthy -> MaterialTheme.colorScheme.primary
        ClusterHealth.Degraded -> MaterialTheme.colorScheme.tertiary
        ClusterHealth.Unreachable -> MaterialTheme.colorScheme.error
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterListScreen(
    clusters: List<ClusterListItemUiModel>,
    connectionState: ClusterConnectionState,
    onClusterSelected: (String) -> Unit,
    onDeleteCluster: (String) -> Unit,
    onAddClusterClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onPodsClick: () -> Unit = {},
    activeContextName: String? = null,
    modifier: Modifier = Modifier,
) {
    val addFabCd = stringResource(id = R.string.cluster_list_add_fab_cd)
    val resolvedActiveContext = activeContextName
        ?: (connectionState as? ClusterConnectionState.Connected)?.contextName

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.cluster_list_title)) },
                actions = {
                    IconButton(onClick = onPodsClick) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(id = R.string.cluster_list_pods_button_cd),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClusterClick,
                modifier = Modifier.semantics { contentDescription = addFabCd },
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            ConnectionStateSection(
                connectionState = connectionState,
                onDisconnectClick = onDisconnectClick,
                modifier = Modifier.padding(MaterialTheme.spacing.medium),
            )

            if (clusters.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(id = R.string.cluster_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(clusters, key = { it.contextName }) { cluster ->
                        val isActive = cluster.contextName == resolvedActiveContext
                        val isConnected = connectionState is ClusterConnectionState.Connected &&
                            connectionState.contextName == cluster.contextName
                        val itemCd = stringResource(id = R.string.cluster_list_item_cd, cluster.contextName)
                        val deleteCd = stringResource(id = R.string.cluster_list_delete_cd, cluster.contextName)
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                val isDismiss =
                                    value == SwipeToDismissBoxValue.EndToStart ||
                                        value == SwipeToDismissBoxValue.StartToEnd
                                if (isDismiss) {
                                    onDeleteCluster(cluster.contextName)
                                }
                                isDismiss
                            },
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = MaterialTheme.spacing.medium),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.cluster_list_delete_label),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { contentDescription = deleteCd },
                                    )
                                }
                            },
                        ) {
                            val activeRowBackground = MaterialTheme.colorScheme.surfaceContainerHighest
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isActive) {
                                            activeRowBackground
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .clickable { onClusterSelected(cluster.contextName) }
                                    .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small)
                                    .semantics { contentDescription = itemCd },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val statusColor = if (isConnected) {
                                    Color(0xFF2E7D32)
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(MaterialTheme.shapes.extraLarge)
                                        .background(statusColor),
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = MaterialTheme.spacing.small),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = cluster.contextName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = cluster.serverUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (isActive) {
                                    androidx.compose.material3.Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(id = R.string.cluster_list_active_badge),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 1.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AddClusterScreen(
    inputMode: AddClusterInputMode,
    kubeConfigText: String,
    server: String,
    token: String,
    insecureTlsEnabled: Boolean,
    showInsecureTlsWarningDialog: Boolean,
    connectionState: ClusterConnectionState,
    onInputModeChange: (AddClusterInputMode) -> Unit,
    onKubeConfigTextChange: (String) -> Unit,
    onServerChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onInsecureTlsToggleRequested: (Boolean) -> Unit,
    onConfirmEnableInsecureTls: () -> Unit,
    onDismissInsecureTlsWarning: () -> Unit,
    onSaveClick: () -> Unit,
    onCancelClick: () -> Unit,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnecting = connectionState is ClusterConnectionState.Connecting
    val formValid = when (inputMode) {
        AddClusterInputMode.KUBECONFIG -> kubeConfigText.isNotBlank()
        AddClusterInputMode.MANUAL -> server.isNotBlank() && token.isNotBlank()
    }

    val cancelCd = stringResource(id = R.string.add_cluster_cancel_button_cd)
    val saveCd = stringResource(id = R.string.add_cluster_save_button_cd)
    val connectCd = stringResource(id = R.string.add_cluster_connect_button_cd)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(text = stringResource(id = R.string.add_cluster_title)) })
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(MaterialTheme.spacing.medium),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onCancelClick,
                    modifier = Modifier.semantics { contentDescription = cancelCd },
                ) {
                    Text(text = stringResource(id = R.string.add_cluster_cancel_button))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    Button(
                        onClick = onSaveClick,
                        enabled = formValid && !isConnecting,
                        modifier = Modifier.semantics { contentDescription = saveCd },
                    ) {
                        Text(text = stringResource(id = R.string.add_cluster_save_button))
                    }
                    Button(
                        onClick = onConnectClick,
                        enabled = formValid && !isConnecting,
                        modifier = Modifier.semantics { contentDescription = connectCd },
                    ) {
                        Text(text = stringResource(id = R.string.add_cluster_connect_button))
                    }
                }
            }
        },
    ) { paddingValues ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(MaterialTheme.spacing.medium)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            ConnectionStateSection(
                connectionState = connectionState,
                onDisconnectClick = {},
            )

            AddClusterModeSwitcher(
                inputMode = inputMode,
                onInputModeChange = onInputModeChange,
            )

            when (inputMode) {
                AddClusterInputMode.KUBECONFIG -> {
                    val kubeConfigCd = stringResource(id = R.string.add_cluster_kubeconfig_field_cd)
                    OutlinedTextField(
                        value = kubeConfigText,
                        onValueChange = onKubeConfigTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = kubeConfigCd },
                        minLines = 6,
                        label = { Text(text = stringResource(id = R.string.add_cluster_kubeconfig_label)) },
                        placeholder = {
                            Text(text = stringResource(id = R.string.add_cluster_kubeconfig_placeholder))
                        },
                    )
                }

                AddClusterInputMode.MANUAL -> {
                    val serverCd = stringResource(id = R.string.add_cluster_server_field_cd)
                    val tokenCd = stringResource(id = R.string.add_cluster_token_field_cd)
                    OutlinedTextField(
                        value = server,
                        onValueChange = onServerChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = serverCd },
                        label = { Text(text = stringResource(id = R.string.add_cluster_server_label)) },
                        placeholder = { Text(text = stringResource(id = R.string.add_cluster_server_placeholder)) },
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = onTokenChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = tokenCd },
                        label = { Text(text = stringResource(id = R.string.add_cluster_token_label)) },
                        placeholder = { Text(text = stringResource(id = R.string.add_cluster_token_placeholder)) },
                    )
                    val insecureTlsToggleCd = stringResource(id = R.string.add_cluster_insecure_tls_toggle_cd)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(id = R.string.add_cluster_insecure_tls_toggle_label),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = insecureTlsEnabled,
                            onCheckedChange = onInsecureTlsToggleRequested,
                            modifier = Modifier.semantics { contentDescription = insecureTlsToggleCd },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MaterialTheme.spacing.medium),
            ) {
                // Spacer for comfortable scroll end with long kubeconfig input.
            }
        }

        if (showInsecureTlsWarningDialog) {
            AlertDialog(
                onDismissRequest = onDismissInsecureTlsWarning,
                title = { Text(text = stringResource(id = R.string.add_cluster_insecure_tls_warning_title)) },
                text = { Text(text = stringResource(id = R.string.add_cluster_insecure_tls_warning_message)) },
                confirmButton = {
                    TextButton(onClick = onConfirmEnableInsecureTls) {
                        Text(text = stringResource(id = R.string.add_cluster_insecure_tls_warning_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissInsecureTlsWarning) {
                        Text(text = stringResource(id = R.string.add_cluster_insecure_tls_warning_dismiss))
                    }
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AddClusterModeSwitcher(
    inputMode: AddClusterInputMode,
    onInputModeChange: (AddClusterInputMode) -> Unit,
) {
    val kubeconfigModeCd = stringResource(id = R.string.add_cluster_mode_kubeconfig_cd)
    val manualModeCd = stringResource(id = R.string.add_cluster_mode_manual_cd)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = inputMode == AddClusterInputMode.KUBECONFIG,
            onClick = { onInputModeChange(AddClusterInputMode.KUBECONFIG) },
            shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.semantics { contentDescription = kubeconfigModeCd },
        ) {
            Text(text = stringResource(id = R.string.add_cluster_mode_kubeconfig))
        }
        SegmentedButton(
            selected = inputMode == AddClusterInputMode.MANUAL,
            onClick = { onInputModeChange(AddClusterInputMode.MANUAL) },
            shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.semantics { contentDescription = manualModeCd },
        ) {
            Text(text = stringResource(id = R.string.add_cluster_mode_manual))
        }
    }
}

@Composable
private fun ConnectionStateSection(
    connectionState: ClusterConnectionState,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (connectionState) {
        ClusterConnectionState.Disconnected -> {
            Text(
                text = stringResource(id = R.string.connection_state_disconnected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )
        }

        is ClusterConnectionState.Connecting -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        id = R.string.connection_state_connecting,
                        connectionState.contextName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CircularProgressIndicator()
            }
        }

        is ClusterConnectionState.Connected -> {
            val disconnectCd = stringResource(id = R.string.connection_disconnect_button_cd)
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        id = R.string.connection_state_connected,
                        connectionState.contextName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(
                    onClick = onDisconnectClick,
                    modifier = Modifier.semantics { contentDescription = disconnectCd },
                ) {
                    Text(text = stringResource(id = R.string.connection_disconnect_button))
                }
            }
        }

        is ClusterConnectionState.Failed -> {
            Column(modifier = modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        id = R.string.connection_state_failed,
                        connectionState.contextName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = connectionState.reason
                        ?: stringResource(id = R.string.connection_state_failed_no_reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private val MaterialTheme.spacing: Spacing
    @Composable get() = Spacing()

private class Spacing {
    val small = 8.dp
    val medium = 16.dp
}

@Preview(showBackground = true)
@Composable
private fun DashboardScreenPreviewLight() {
    MaterialTheme {
        DashboardScreen(
            state = DashboardUiState.Success(
                summaries = listOf(
                    ClusterSummary(
                        contextName = "dev-cluster",
                        podCount = 42,
                        nodeCount = 6,
                        warningEventCount = 2,
                        cpuUsagePct = 61f,
                        memoryUsagePct = 48f,
                        health = ClusterHealth.Degraded,
                    ),
                    ClusterSummary(
                        contextName = "prod-cluster",
                        podCount = 123,
                        nodeCount = 14,
                        warningEventCount = 0,
                        cpuUsagePct = 72f,
                        memoryUsagePct = 66f,
                        health = ClusterHealth.Healthy,
                    ),
                ),
            ),
            isRefreshing = false,
            onRefresh = {},
            onClusterClick = {},
            onAddClusterClick = {},
        )
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardScreenPreviewDark() {
    MaterialTheme {
        DashboardScreen(
            state = DashboardUiState.Success(
                summaries = listOf(
                    ClusterSummary(
                        contextName = "edge-cluster",
                        podCount = 0,
                        nodeCount = 0,
                        warningEventCount = 0,
                        cpuUsagePct = 0f,
                        memoryUsagePct = 0f,
                        health = ClusterHealth.Unreachable,
                    ),
                ),
            ),
            isRefreshing = false,
            onRefresh = {},
            onClusterClick = {},
            onAddClusterClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ClusterListScreenPreviewLight() {
    MaterialTheme {
        ClusterListScreen(
            clusters = listOf(
                ClusterListItemUiModel("dev-cluster", "https://dev.example.com"),
                ClusterListItemUiModel("prod-cluster", "https://prod.example.com"),
            ),
            activeContextName = "dev-cluster",
            connectionState = ClusterConnectionState.Connected(contextName = "dev-cluster"),
            onClusterSelected = {},
            onDeleteCluster = {},
            onAddClusterClick = {},
            onDisconnectClick = {},
        )
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ClusterListScreenPreviewDark() {
    MaterialTheme {
        ClusterListScreen(
            clusters = listOf(
                ClusterListItemUiModel("dev-cluster", "https://dev.example.com"),
            ),
            activeContextName = null,
            connectionState = ClusterConnectionState.Failed("dev-cluster", "TLS handshake failed"),
            onClusterSelected = {},
            onDeleteCluster = {},
            onAddClusterClick = {},
            onDisconnectClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AddClusterScreenPreviewLight() {
    MaterialTheme {
        AddClusterScreen(
            inputMode = AddClusterInputMode.KUBECONFIG,
            kubeConfigText = "apiVersion: v1",
            server = "",
            token = "",
            insecureTlsEnabled = false,
            showInsecureTlsWarningDialog = false,
            connectionState = ClusterConnectionState.Connecting(contextName = "dev-cluster"),
            onInputModeChange = {},
            onKubeConfigTextChange = {},
            onServerChange = {},
            onTokenChange = {},
            onInsecureTlsToggleRequested = {},
            onConfirmEnableInsecureTls = {},
            onDismissInsecureTlsWarning = {},
            onSaveClick = {},
            onCancelClick = {},
            onConnectClick = {},
        )
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AddClusterScreenPreviewDark() {
    MaterialTheme {
        AddClusterScreen(
            inputMode = AddClusterInputMode.MANUAL,
            kubeConfigText = "",
            server = "https://k8s.example.com",
            token = "***",
            insecureTlsEnabled = false,
            showInsecureTlsWarningDialog = false,
            connectionState = ClusterConnectionState.Disconnected,
            onInputModeChange = {},
            onKubeConfigTextChange = {},
            onServerChange = {},
            onTokenChange = {},
            onInsecureTlsToggleRequested = {},
            onConfirmEnableInsecureTls = {},
            onDismissInsecureTlsWarning = {},
            onSaveClick = {},
            onCancelClick = {},
            onConnectClick = {},
        )
    }
}
