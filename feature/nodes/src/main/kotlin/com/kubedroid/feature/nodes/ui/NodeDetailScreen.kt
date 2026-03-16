package com.kubedroid.feature.nodes.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.core.network.nodes.Node
import com.kubedroid.core.network.nodes.NodeCondition
import com.kubedroid.feature.nodes.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NodeDetailRoute(
    nodeName: String,
    viewModel: NodeDetailViewModel,
    onBackClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(nodeName) {
        viewModel.onIntent(NodeDetailIntent.Load(nodeName))
    }

    NodeDetailScreen(
        state = state,
        onBackClick = onBackClick,
        onTabSelected = { viewModel.onIntent(NodeDetailIntent.SelectTab(it)) },
        onRequestActionConfirmation = {
            viewModel.onIntent(NodeDetailIntent.RequestActionConfirmation(it))
        },
        onDismissActionConfirmation = {
            viewModel.onIntent(NodeDetailIntent.DismissActionConfirmation)
        },
        onConfirmAction = { viewModel.onIntent(NodeDetailIntent.ConfirmAction) },
        onConsumeActionMessage = { viewModel.onIntent(NodeDetailIntent.ConsumeActionMessage) },
        onDismissDrainDialog = { viewModel.onIntent(NodeDetailIntent.DismissDrainProgressDialog) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(
    state: NodeDetailUiState,
    onBackClick: () -> Unit,
    onTabSelected: (NodeDetailTab) -> Unit,
    onRequestActionConfirmation: (NodeActionType) -> Unit,
    onDismissActionConfirmation: () -> Unit,
    onConfirmAction: () -> Unit,
    onConsumeActionMessage: () -> Unit,
    onDismissDrainDialog: () -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.nodes_screen_padding)
    val contentSpacing = dimensionResource(id = R.dimen.nodes_section_spacing)
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarText = state.actionMessage?.toMessageText()

    LaunchedEffect(snackbarText) {
        val message = snackbarText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onConsumeActionMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(id = R.string.node_detail_title, state.nodeName))
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.node_detail_back_cd),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = screenPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            TabRow(selectedTabIndex = state.selectedTab.ordinal) {
                Tab(
                    selected = state.selectedTab == NodeDetailTab.INFO,
                    onClick = { onTabSelected(NodeDetailTab.INFO) },
                    text = { Text(text = stringResource(id = R.string.node_detail_tab_info)) },
                )
                Tab(
                    selected = state.selectedTab == NodeDetailTab.CONDITIONS,
                    onClick = { onTabSelected(NodeDetailTab.CONDITIONS) },
                    text = { Text(text = stringResource(id = R.string.node_detail_tab_conditions)) },
                )
                Tab(
                    selected = state.selectedTab == NodeDetailTab.PODS,
                    onClick = { onTabSelected(NodeDetailTab.PODS) },
                    text = { Text(text = stringResource(id = R.string.node_detail_tab_pods)) },
                )
            }

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
                    Text(
                        text = state.error.localizedMessage
                            ?: stringResource(id = R.string.node_detail_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.node == null -> {
                    Text(
                        text = stringResource(id = R.string.node_detail_not_found),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {
                    NodeActionBar(
                        unschedulable = state.node.unschedulable,
                        isActionInProgress = state.isActionInProgress,
                        onCordonClick = {
                            onRequestActionConfirmation(NodeActionType.CORDON)
                        },
                        onUncordonClick = {
                            onRequestActionConfirmation(NodeActionType.UNCORDON)
                        },
                        onDrainClick = {
                            onRequestActionConfirmation(NodeActionType.DRAIN)
                        },
                    )

                    when (state.selectedTab) {
                        NodeDetailTab.INFO -> NodeInfoTab(node = state.node)
                        NodeDetailTab.CONDITIONS -> NodeConditionsTab(conditions = state.node.conditions)
                        NodeDetailTab.PODS -> NodePodsTab(state = state.drainState)
                    }
                }
            }
        }
    }

    state.activeConfirmation?.let { action ->
        NodeActionConfirmationDialog(
            action = action,
            nodeName = state.nodeName,
            onConfirm = onConfirmAction,
            onDismiss = onDismissActionConfirmation,
        )
    }

    DrainProgressDialog(
        state = state.drainState,
        onDismissRequest = onDismissDrainDialog,
    )
}

@Composable
private fun NodeInfoTab(
    node: Node,
) {
    val rowSpacing = dimensionResource(id = R.dimen.nodes_info_row_spacing)

    Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
        InfoRow(label = stringResource(id = R.string.node_info_name), value = node.name)
        InfoRow(
            label = stringResource(id = R.string.node_info_roles),
            value = if (node.roles.isEmpty()) {
                stringResource(id = R.string.nodes_list_role_worker)
            } else {
                node.roles.joinToString()
            },
        )
        InfoRow(
            label = stringResource(id = R.string.node_info_ready),
            value = if (node.ready) {
                stringResource(id = R.string.nodes_status_ready)
            } else {
                stringResource(id = R.string.nodes_status_not_ready)
            },
        )
        InfoRow(
            label = stringResource(id = R.string.node_info_schedulable),
            value = if (node.unschedulable) {
                stringResource(id = R.string.node_schedulable_no)
            } else {
                stringResource(id = R.string.node_schedulable_yes)
            },
        )
        InfoRow(
            label = stringResource(id = R.string.node_info_kubelet),
            value = node.kubeletVersion ?: stringResource(id = R.string.node_value_unknown),
        )
        InfoRow(
            label = stringResource(id = R.string.node_info_internal_ip),
            value = node.internalIp ?: stringResource(id = R.string.node_value_unknown),
        )
        InfoRow(
            label = stringResource(id = R.string.node_info_external_ip),
            value = node.externalIp ?: stringResource(id = R.string.node_value_unknown),
        )
        InfoRow(
            label = stringResource(id = R.string.nodes_cpu_label),
            value = node.metrics?.cpuUsagePercent?.let { value ->
                stringResource(id = R.string.nodes_metric_percent_value, value)
            } ?: stringResource(id = R.string.nodes_metric_unavailable),
        )
        InfoRow(
            label = stringResource(id = R.string.nodes_memory_label),
            value = node.metrics?.memoryUsagePercent?.let { value ->
                stringResource(id = R.string.nodes_metric_percent_value, value)
            } ?: stringResource(id = R.string.nodes_metric_unavailable),
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value)
    }
}

@Composable
private fun NodeConditionsTab(
    conditions: List<NodeCondition>,
) {
    if (conditions.isEmpty()) {
        Text(text = stringResource(id = R.string.node_conditions_empty))
        return
    }

    val itemSpacing = dimensionResource(id = R.dimen.nodes_section_spacing)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
        items(items = conditions) { condition ->
            Column(verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                Text(
                    text = condition.type,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(id = R.string.node_condition_status, condition.status),
                    style = MaterialTheme.typography.bodyMedium,
                )
                condition.reason?.let { reason ->
                    Text(
                        text = stringResource(id = R.string.node_condition_reason, reason),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                condition.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                condition.lastTransitionTimeEpochMillis?.let { epochMillis ->
                    Text(
                        text = stringResource(
                            id = R.string.node_condition_last_transition,
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(epochMillis)),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NodePodsTab(
    state: DrainProgressUiState,
) {
    val events = state.events.filter { event ->
        event.messageType == DrainEventMessageType.EVICTION_ATTEMPT ||
            event.messageType == DrainEventMessageType.WAITING_ON_PDB ||
            event.messageType == DrainEventMessageType.POD_EVICTED
    }

    if (events.isEmpty()) {
        Text(text = stringResource(id = R.string.node_pods_empty_hint))
        return
    }

    val itemSpacing = dimensionResource(id = R.dimen.nodes_section_spacing)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
        items(items = events) { event ->
            Text(
                text = stringResource(
                    id = R.string.node_pods_item,
                    event.namespace.orEmpty(),
                    event.podName.orEmpty(),
                    event.messageType.toPodsStateLabel(),
                ),
            )
        }
    }
}

@Composable
private fun DrainEventMessageType.toPodsStateLabel(): String {
    return when (this) {
        DrainEventMessageType.EVICTION_ATTEMPT -> stringResource(id = R.string.node_pods_state_eviction_attempt)
        DrainEventMessageType.WAITING_ON_PDB -> stringResource(id = R.string.node_pods_state_waiting_pdb)
        DrainEventMessageType.POD_EVICTED -> stringResource(id = R.string.node_pods_state_evicted)
        DrainEventMessageType.STARTED,
        DrainEventMessageType.COMPLETED_SUCCESS,
        DrainEventMessageType.COMPLETED_NOT_FOUND,
        DrainEventMessageType.COMPLETED_FORBIDDEN,
        DrainEventMessageType.COMPLETED_CONFLICT,
        DrainEventMessageType.COMPLETED_FAILURE,
        -> stringResource(id = R.string.node_value_unknown)
    }
}

@Composable
private fun NodeActionMessage.toMessageText(): String {
    return when (this) {
        is NodeActionMessage.Success -> when (action) {
            NodeActionType.CORDON -> stringResource(id = R.string.node_action_cordon_success)
            NodeActionType.UNCORDON -> stringResource(id = R.string.node_action_uncordon_success)
            NodeActionType.DRAIN -> stringResource(id = R.string.node_action_drain_success)
        }

        is NodeActionMessage.Failure -> when (action) {
            NodeActionType.CORDON -> actionFailureText(
                notFound = R.string.node_action_cordon_not_found,
                forbidden = R.string.node_action_cordon_forbidden,
                conflict = R.string.node_action_cordon_conflict,
                error = error,
            )

            NodeActionType.UNCORDON -> actionFailureText(
                notFound = R.string.node_action_uncordon_not_found,
                forbidden = R.string.node_action_uncordon_forbidden,
                conflict = R.string.node_action_uncordon_conflict,
                error = error,
            )

            NodeActionType.DRAIN -> actionFailureText(
                notFound = R.string.node_action_drain_not_found,
                forbidden = R.string.node_action_drain_forbidden,
                conflict = R.string.node_action_drain_conflict,
                error = error,
            )
        }
    }
}

@Composable
private fun actionFailureText(
    notFound: Int,
    forbidden: Int,
    conflict: Int,
    error: NodeActionError,
): String {
    return when (error) {
        NodeActionError.NOT_FOUND -> stringResource(id = notFound)
        NodeActionError.FORBIDDEN -> stringResource(id = forbidden)
        NodeActionError.CONFLICT -> stringResource(id = conflict)
        NodeActionError.UNKNOWN -> stringResource(id = R.string.node_action_generic_failure)
    }
}
