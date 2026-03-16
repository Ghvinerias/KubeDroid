package com.kubedroid.feature.pods.resources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import com.kubedroid.core.ui.namespace.NamespaceSelector
import com.kubedroid.feature.metrics.ui.PodMetricsSummaryRow
import com.kubedroid.feature.pods.R
import kotlinx.coroutines.launch

@Composable
fun ResourceListRoute(
    viewModel: ResourceListViewModel,
    onResourceClick: (ResourceListItem) -> Unit = {},
    useTwoPane: Boolean = false,
    detailPane: (@Composable () -> Unit)? = null,
    hingePadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.onIntent(ResourceListIntent.Load)
    }

    ResourceListScreen(
        state = uiState,
        onNamespaceClick = { viewModel.onIntent(ResourceListIntent.OpenNamespaceSwitcher) },
        onNamespaceDismiss = { viewModel.onIntent(ResourceListIntent.DismissNamespaceSwitcher) },
        onNamespaceSelected = { viewModel.onIntent(ResourceListIntent.SelectNamespace(it)) },
        onRetryClick = { viewModel.onIntent(ResourceListIntent.Retry) },
        onResourceClick = onResourceClick,
        useTwoPane = useTwoPane,
        detailPane = detailPane,
        hingePadding = hingePadding,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ResourceListScreen(
    state: ResourceListScreenState,
    onNamespaceClick: () -> Unit,
    onNamespaceDismiss: () -> Unit,
    onNamespaceSelected: (String?) -> Unit,
    onRetryClick: () -> Unit,
    onResourceClick: (ResourceListItem) -> Unit = {},
    useTwoPane: Boolean = false,
    detailPane: (@Composable () -> Unit)? = null,
    hingePadding: Dp = 0.dp,
) {
    val screenPadding = dimensionResource(id = R.dimen.resource_list_screen_padding)
    val rowPadding = dimensionResource(id = R.dimen.resource_list_item_padding)
    val contentSpacing = dimensionResource(id = R.dimen.resource_list_content_spacing)
    val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<Unit>()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.resource_list_title)) },
                actions = {
                    IconButton(onClick = onRetryClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.resource_list_refresh_cd),
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
                        ResourceListPane(
                            state = state,
                            screenPadding = screenPadding,
                            rowPadding = rowPadding,
                            contentSpacing = contentSpacing,
                            onNamespaceClick = onNamespaceClick,
                            onNamespaceDismiss = onNamespaceDismiss,
                            onNamespaceSelected = onNamespaceSelected,
                            onRetryClick = onRetryClick,
                            onResourceClick = { item ->
                                onResourceClick(item)
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
            ResourceListPane(
                state = state,
                screenPadding = screenPadding,
                rowPadding = rowPadding,
                contentSpacing = contentSpacing,
                onNamespaceClick = onNamespaceClick,
                onNamespaceDismiss = onNamespaceDismiss,
                onNamespaceSelected = onNamespaceSelected,
                onRetryClick = onRetryClick,
                onResourceClick = onResourceClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        }
    }

}

@Composable
private fun ResourceListPane(
    state: ResourceListScreenState,
    screenPadding: androidx.compose.ui.unit.Dp,
    rowPadding: androidx.compose.ui.unit.Dp,
    contentSpacing: androidx.compose.ui.unit.Dp,
    onNamespaceClick: () -> Unit,
    onNamespaceDismiss: () -> Unit,
    onNamespaceSelected: (String?) -> Unit,
    onRetryClick: () -> Unit,
    onResourceClick: (ResourceListItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Background)
            .padding(horizontal = screenPadding),
        verticalArrangement = Arrangement.spacedBy(contentSpacing),
    ) {
        NamespaceSwitcherButton(
            state = state,
            onNamespaceClick = onNamespaceClick,
            onNamespaceDismiss = onNamespaceDismiss,
            onNamespaceSelected = onNamespaceSelected,
        )

        if (state.isDataFromCache) {
            StaleDataBanner(lastFetchedAtEpochMillis = state.lastUpdatedAtEpochMillis)
        }

        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val listState = state.listState) {
                ResourceListUiState.Loading -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(8) {
                            ShimmerRow()
                            HorizontalDivider(color = SurfaceBorder, thickness = 1.dp)
                        }
                    }
                }

                ResourceListUiState.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val namespaceLabel = if (state.selectedNamespace.isAllNamespacesSelection()) {
                            "all namespaces"
                        } else {
                            state.selectedNamespace
                        }
                        Text(
                            text = "$ kubectl get pods\nNo resources found in $namespaceLabel namespace.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                is ResourceListUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(contentSpacing),
                        ) {
                            Text(
                                text = listState.cause?.localizedMessage
                                    ?: stringResource(id = R.string.resource_list_error),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(onClick = onRetryClick) {
                                Text(text = stringResource(id = R.string.resource_list_retry))
                            }
                        }
                    }
                }

                is ResourceListUiState.Success -> {
                    val visibleItems by remember(listState.items, state.selectedNamespace) {
                        derivedStateOf {
                            val selectedNamespace = state.selectedNamespace
                            if (selectedNamespace.isAllNamespacesSelection() || selectedNamespace.isBlank()) {
                                listState.items
                            } else {
                                listState.items.filter { it.namespace == selectedNamespace }
                            }
                        }
                    }
                    val showNamespaceLabel = state.selectedNamespace.isAllNamespacesSelection()
                    ResourceTableHeader(
                        columns = listOf(
                            "Name" to 1f,
                            "Status" to 0.7f,
                            "Restarts" to 0.45f,
                            "Age" to 0.35f,
                        ),
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = visibleItems,
                            key = { it.stableKey },
                        ) { item ->
                            key(item.stableKey) {
                                ResourceRow(
                                    name = item.name,
                                    namespace = item.namespace,
                                    status = item.status,
                                    age = "-",
                                    restarts = 0,
                                    node = "",
                                    showNamespace = showNamespaceLabel,
                                    onClick = { onResourceClick(item) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = rowPadding / 2),
                                )
                                HorizontalDivider(color = SurfaceBorder, thickness = 1.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NamespaceSwitcherButton(
    state: ResourceListScreenState,
    onNamespaceClick: () -> Unit,
    onNamespaceDismiss: () -> Unit,
    onNamespaceSelected: (String?) -> Unit,
) {
    NamespaceSelector(
        namespaces = state.namespaces,
        selectedNamespace = state.selectedNamespace,
        onNamespaceSelected = onNamespaceSelected,
        isSheetVisible = state.isNamespaceSwitcherVisible,
        onSheetVisibilityChange = { visible ->
            if (visible) {
                onNamespaceClick()
            } else {
                onNamespaceDismiss()
            }
        },
        allowAllNamespaces = true,
    )
}

@Composable
fun ResourceRow(
    item: ResourceListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ResourceRow(
        name = item.name,
        namespace = item.namespace,
        status = item.status,
        age = "-",
        restarts = 0,
        node = "",
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
fun ResourceRow(
    name: String,
    namespace: String,
    status: ResourceStatus,
    age: String,
    restarts: Int,
    node: String,
    showNamespace: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(SurfaceContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showNamespace) {
                Text(
                    text = namespace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }

        Spacer(Modifier.width(12.dp))
        StatusBadge(status = status)
        Spacer(Modifier.width(12.dp))
        Text(
            text = restarts.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (restarts > 0) AccentRed else TextSecondary,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = age,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun StatusBadge(
    status: ResourceStatus,
    modifier: Modifier = Modifier,
) {
    val (label, color, bgColor) = when (status) {
        ResourceStatus.HEALTHY -> Triple("Running", StatusRunning, StatusRunningBg)
        ResourceStatus.WARNING -> Triple("Pending", StatusPending, StatusPendingBg)
        ResourceStatus.ERROR -> Triple("Failed", StatusFailed, StatusFailedBg)
        ResourceStatus.UNKNOWN -> Triple("Unknown", StatusUnknown, Color.Transparent)
    }

    Row(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(3.dp))
            .drawWithContent {
                drawRect(color = color, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
                drawContent()
            }
            .padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(5.dp)) {
            drawCircle(color = color)
        }
        Text(
            text = label.toString().uppercase(),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.6.sp,
        )
    }
}

@Composable
fun ResourceTableHeader(columns: List<Pair<String, Float>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .drawWithContent {
                drawContent()
                drawLine(
                    color = SurfaceBorder,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        columns.forEach { (label, weight) ->
            Text(
                text = label.toString().uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.weight(weight),
                letterSpacing = 0.8.sp,
            )
        }
    }
}

private val Background = Color(0xFF0D0D0F)
private val SurfaceContainer = Color(0xFF141417)
private val SurfaceBorder = Color(0xFF2A2A30)
private val TextPrimary = Color(0xFFF8F8F2)
private val TextSecondary = Color(0xFF8B8B9A)
private val AccentRed = Color(0xFFFF5555)
private val StatusRunning = Color(0xFF50FA7B)
private val StatusPending = Color(0xFFF1FA8C)
private val StatusFailed = Color(0xFFFF5555)
private val StatusUnknown = Color(0xFF55555F)
private val StatusRunningBg = Color(0x1450FA7B)
private val StatusPendingBg = Color(0x14F1FA8C)
private val StatusFailedBg = Color(0x14FF5555)
private val StatusTermBg = Color(0x14FFB86C)
