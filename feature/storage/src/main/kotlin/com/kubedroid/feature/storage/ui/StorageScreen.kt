package com.kubedroid.feature.storage.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.core.network.config.BiometricToken
import com.kubedroid.core.security.BiometricHelper
import com.kubedroid.feature.events.domain.model.ClusterEvent
import com.kubedroid.feature.events.domain.model.ClusterEventType
import com.kubedroid.feature.storage.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StorageRoute(
    viewModel: StorageViewModel,
    onPvcClick: (namespace: String, name: String) -> Unit,
    onConfigMapClick: (namespace: String, name: String) -> Unit,
    onSecretClick: (namespace: String, name: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StorageScreen(
        state = state,
        onTabSelected = viewModel::onTabSelected,
        onRefresh = viewModel::refresh,
        onNamespaceChange = viewModel::onNamespaceChange,
        onConfigMapNamespaceChange = viewModel::onConfigMapNamespaceChange,
        onSecretNamespaceChange = viewModel::onSecretNamespaceChange,
        onPvcClick = onPvcClick,
        onConfigMapClick = onConfigMapClick,
        onSecretClick = onSecretClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    state: StorageUiState,
    onTabSelected: (StorageTab) -> Unit,
    onRefresh: () -> Unit,
    onNamespaceChange: (String) -> Unit,
    onConfigMapNamespaceChange: (String) -> Unit,
    onSecretNamespaceChange: (String) -> Unit,
    onPvcClick: (namespace: String, name: String) -> Unit,
    onConfigMapClick: (namespace: String, name: String) -> Unit,
    onSecretClick: (namespace: String, name: String) -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.storage_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.storage_section_spacing)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.storage_title)) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.storage_refresh_cd),
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
            StorageTabs(
                selectedTab = state.selectedTab,
                onTabSelected = onTabSelected,
            )

            when (state.selectedTab) {
                StorageTab.PVCS -> PvcTabContent(
                    namespace = state.selectedNamespace,
                    namespaces = state.namespaces,
                    pvcState = state.pvcState,
                    items = state.pvcs,
                    onNamespaceChange = onNamespaceChange,
                    onPvcClick = onPvcClick,
                )

                StorageTab.PVS -> PvTabContent(
                    pvsState = state.pvsState,
                    items = state.pvs,
                )

                StorageTab.STORAGE_CLASSES -> StorageClassTabContent(
                    state = state.storageClassState,
                    items = state.storageClasses,
                )

                StorageTab.CONFIG_MAPS -> ConfigMapListScreen(
                    namespace = state.selectedConfigMapNamespace,
                    namespaces = state.namespaces,
                    listState = state.configMapState,
                    items = state.configMaps,
                    onNamespaceChange = onConfigMapNamespaceChange,
                    onConfigMapClick = onConfigMapClick,
                )

                StorageTab.SECRETS -> SecretListScreen(
                    namespace = state.selectedSecretNamespace,
                    namespaces = state.namespaces,
                    listState = state.secretState,
                    items = state.secrets,
                    onNamespaceChange = onSecretNamespaceChange,
                    onSecretClick = onSecretClick,
                )
            }
        }
    }
}

@Composable
private fun StorageTabs(
    selectedTab: StorageTab,
    onTabSelected: (StorageTab) -> Unit,
) {
    val tabs = listOf(StorageTab.PVCS, StorageTab.PVS, StorageTab.STORAGE_CLASSES, StorageTab.CONFIG_MAPS, StorageTab.SECRETS)
    TabRow(selectedTabIndex = tabs.indexOf(selectedTab)) {
        tabs.forEach { tab ->
            val title = when (tab) {
                StorageTab.PVCS -> stringResource(id = R.string.storage_tab_pvcs)
                StorageTab.PVS -> stringResource(id = R.string.storage_tab_pvs)
                StorageTab.STORAGE_CLASSES -> stringResource(id = R.string.storage_tab_classes)
                StorageTab.CONFIG_MAPS -> stringResource(id = R.string.storage_tab_config_maps)
                StorageTab.SECRETS -> stringResource(id = R.string.storage_tab_secrets)
            }
            Tab(
                selected = tab == selectedTab,
                onClick = { onTabSelected(tab) },
                text = { Text(text = title) },
            )
        }
    }
}

@Composable
private fun PvcTabContent(
    namespace: String,
    namespaces: List<String>,
    pvcState: StorageListState,
    items: List<PvcListItemUi>,
    onNamespaceChange: (String) -> Unit,
    onPvcClick: (namespace: String, name: String) -> Unit,
) {
    val sectionSpacing = dimensionResource(id = R.dimen.storage_section_spacing)
    val fieldValue = namespace

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        NamespaceSelector(
            namespace = fieldValue,
            label = stringResource(id = R.string.storage_pvc_namespace_label),
            options = buildNamespaceOptions(
                selectedNamespace = fieldValue,
                values = namespaces,
            ),
            onNamespaceSelected = onNamespaceChange,
        )

        when (pvcState) {
            StorageListState.Loading -> CenteredLoading()
            StorageListState.Empty -> CenteredMessage(text = stringResource(id = R.string.storage_pvc_empty))
            is StorageListState.Error -> CenteredError(
                message = pvcState.cause?.localizedMessage
                    ?: stringResource(id = R.string.storage_load_error),
            )

            StorageListState.Success -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = items, key = { "${it.namespace}:${it.name}" }) { item ->
                        PvcListItem(
                            item = item,
                            onClick = { onPvcClick(item.namespace, item.name) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PvTabContent(
    pvsState: StorageListState,
    items: List<PvListItemUi>,
) {
    when (pvsState) {
        StorageListState.Loading -> CenteredLoading()
        StorageListState.Empty -> CenteredMessage(text = stringResource(id = R.string.storage_pv_empty))
        is StorageListState.Error -> CenteredError(
            message = pvsState.cause?.localizedMessage
                ?: stringResource(id = R.string.storage_load_error),
        )

        StorageListState.Success -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = items, key = { it.name }) { item ->
                    PvListItem(
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
private fun StorageClassTabContent(
    state: StorageListState,
    items: List<StorageClassListItemUi>,
) {
    when (state) {
        StorageListState.Loading -> CenteredLoading()
        StorageListState.Empty -> CenteredMessage(text = stringResource(id = R.string.storage_class_empty))
        is StorageListState.Error -> CenteredError(
            message = state.cause?.localizedMessage
                ?: stringResource(id = R.string.storage_load_error),
        )

        StorageListState.Success -> StorageClassList(items = items)
    }
}

@Composable
fun ConfigMapListScreen(
    namespace: String,
    namespaces: List<String>,
    listState: StorageListState,
    items: List<ConfigMapListItemUi>,
    onNamespaceChange: (String) -> Unit,
    onConfigMapClick: (namespace: String, name: String) -> Unit,
) {
    NamespaceScopedListContent(
        namespace = namespace,
        label = stringResource(id = R.string.storage_config_map_namespace_label),
        namespaceOptions = buildNamespaceOptions(
            selectedNamespace = namespace,
            values = namespaces,
        ),
        listState = listState,
        emptyText = stringResource(id = R.string.storage_config_map_empty),
        onNamespaceChange = onNamespaceChange,
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = items, key = { "${it.namespace}:${it.name}" }) { item ->
                ConfigMapListItem(
                    item = item,
                    onClick = { onConfigMapClick(item.namespace, item.name) },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun SecretListScreen(
    namespace: String,
    namespaces: List<String>,
    listState: StorageListState,
    items: List<SecretListItemUi>,
    onNamespaceChange: (String) -> Unit,
    onSecretClick: (namespace: String, name: String) -> Unit,
) {
    NamespaceScopedListContent(
        namespace = namespace,
        label = stringResource(id = R.string.storage_secret_namespace_label),
        namespaceOptions = buildNamespaceOptions(
            selectedNamespace = namespace,
            values = namespaces,
        ),
        listState = listState,
        emptyText = stringResource(id = R.string.storage_secret_empty),
        onNamespaceChange = onNamespaceChange,
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = items, key = { "${it.namespace}:${it.name}" }) { item ->
                SecretListItem(
                    item = item,
                    onClick = { onSecretClick(item.namespace, item.name) },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun NamespaceScopedListContent(
    namespace: String,
    label: String,
    namespaceOptions: List<String>,
    listState: StorageListState,
    emptyText: String,
    onNamespaceChange: (String) -> Unit,
    successContent: @Composable () -> Unit,
) {
    val sectionSpacing = dimensionResource(id = R.dimen.storage_section_spacing)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        NamespaceSelector(
            namespace = namespace,
            label = label,
            options = namespaceOptions,
            onNamespaceSelected = onNamespaceChange,
        )
        when (listState) {
            StorageListState.Loading -> CenteredLoading()
            StorageListState.Empty -> CenteredMessage(text = emptyText)
            is StorageListState.Error -> CenteredError(
                message = listState.cause?.localizedMessage
                    ?: stringResource(id = R.string.storage_load_error),
            )

            StorageListState.Success -> successContent()
        }
    }
}

private fun buildNamespaceOptions(
    selectedNamespace: String,
    values: List<String>,
): List<String> {
    return buildSet {
        add(DEFAULT_NAMESPACE)
        add(selectedNamespace)
        values.forEach { add(it) }
    }
        .filter { it.isNotBlank() }
        .sorted()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NamespaceSelector(
    namespace: String,
    label: String,
    options: List<String>,
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
private fun ConfigMapListItem(
    item: ConfigMapListItemUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemPadding = dimensionResource(id = R.dimen.storage_item_padding)
    val rowSpacing = dimensionResource(id = R.dimen.storage_row_spacing)
    Card(
        modifier = modifier
            .padding(vertical = rowSpacing)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Text(text = item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(id = R.string.storage_config_map_key_count, item.keyCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = stringResource(id = R.string.storage_config_map_age, item.age), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SecretListItem(
    item: SecretListItemUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemPadding = dimensionResource(id = R.dimen.storage_item_padding)
    val rowSpacing = dimensionResource(id = R.dimen.storage_row_spacing)
    Card(
        modifier = modifier
            .padding(vertical = rowSpacing)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Text(text = item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = stringResource(id = R.string.storage_secret_type, item.type), style = MaterialTheme.typography.bodyMedium)
            Text(text = stringResource(id = R.string.storage_secret_key_count, item.keyCount), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PvcListItem(
    item: PvcListItemUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemPadding = dimensionResource(id = R.dimen.storage_item_padding)
    val rowSpacing = dimensionResource(id = R.dimen.storage_row_spacing)
    Card(
        modifier = modifier
            .padding(vertical = rowSpacing)
            .clickable(onClick = onClick),
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

                val isBound = item.bindingStatus == PvcBindingStatus.BOUND
                Surface(
                    color = if (isBound) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = if (isBound) {
                            stringResource(id = R.string.storage_pvc_bound)
                        } else {
                            stringResource(id = R.string.storage_pvc_unbound)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isBound) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(horizontal = itemPadding / 2, vertical = rowSpacing / 2),
                    )
                }
            }

            Text(
                text = stringResource(id = R.string.storage_pvc_capacity, item.capacity),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.storage_pvc_access_mode, item.accessMode),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.storage_pvc_storage_class, item.storageClass),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun PvListItem(
    item: PvListItemUi,
    modifier: Modifier = Modifier,
) {
    val itemPadding = dimensionResource(id = R.dimen.storage_item_padding)
    val rowSpacing = dimensionResource(id = R.dimen.storage_row_spacing)

    Card(
        modifier = modifier.padding(vertical = rowSpacing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(id = R.string.storage_pv_capacity, item.capacity),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.storage_pv_reclaim_policy, item.reclaimPolicy),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.storage_pv_status, item.status),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun StorageClassList(
    items: List<StorageClassListItemUi>,
    modifier: Modifier = Modifier,
) {
    val rowSpacing = dimensionResource(id = R.dimen.storage_row_spacing)

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(items = items, key = { it.name }) { item ->
            StorageClassListItem(item = item)
            HorizontalDivider()
        }
    }
}

@Composable
private fun StorageClassListItem(
    item: StorageClassListItemUi,
) {
    val itemPadding = dimensionResource(id = R.dimen.storage_item_padding)
    val rowSpacing = dimensionResource(id = R.dimen.storage_row_spacing)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = rowSpacing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(id = R.string.storage_class_provisioner, item.provisioner),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.storage_class_reclaim_policy, item.reclaimPolicy),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.storage_class_binding_mode, item.volumeBindingMode),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PvcDetailRoute(
    viewModel: PvcDetailViewModel,
    onBackClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PvcDetailScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PvcDetailScreen(
    state: PvcDetailUiState,
    onRefresh: () -> Unit,
    onBackClick: () -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.storage_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.storage_section_spacing)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = state.name) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(id = R.string.storage_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.storage_refresh_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val content = state.contentState) {
            PvcDetailContentState.Loading -> CenteredLoading(modifier = Modifier.padding(innerPadding))
            is PvcDetailContentState.Error -> CenteredError(
                message = content.cause?.localizedMessage
                    ?: stringResource(id = R.string.storage_load_error),
                modifier = Modifier.padding(innerPadding),
            )

            is PvcDetailContentState.Success -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = screenPadding),
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                ) {
                    item {
                        Text(
                            text = stringResource(id = R.string.storage_detail_namespace, content.pvc.namespace),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item {
                        Text(
                            text = stringResource(
                                id = R.string.storage_detail_bound_pv,
                                content.pvc.boundPvName ?: stringResource(id = R.string.storage_unavailable),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item {
                        Text(
                            text = stringResource(
                                id = R.string.storage_detail_access_modes,
                                content.accessModes.ifEmpty {
                                    listOf(stringResource(id = R.string.storage_unavailable))
                                }.joinToString(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item {
                        Text(
                            text = stringResource(id = R.string.storage_detail_events_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    if (content.events.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(id = R.string.storage_detail_events_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(items = content.events, key = { it.uid }) { event ->
                            EventItem(event = event)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigMapDetailRoute(
    viewModel: ConfigMapDetailViewModel,
    onBackClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConfigMapDetailScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onValueChange = viewModel::onValueChange,
        onSave = viewModel::save,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigMapDetailScreen(
    state: ConfigMapDetailUiState,
    onRefresh: () -> Unit,
    onValueChange: (key: String, value: String) -> Unit,
    onSave: () -> Unit,
    onBackClick: () -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.storage_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.storage_section_spacing)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = state.name) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(id = R.string.storage_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.storage_refresh_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val content = state.contentState) {
            ConfigMapDetailContentState.Loading -> CenteredLoading(modifier = Modifier.padding(innerPadding))
            is ConfigMapDetailContentState.Error -> CenteredError(
                message = content.cause?.localizedMessage ?: stringResource(id = R.string.storage_load_error),
                modifier = Modifier.padding(innerPadding),
            )

            is ConfigMapDetailContentState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = screenPadding),
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                ) {
                    Text(
                        text = stringResource(id = R.string.storage_detail_namespace, content.namespace),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                    ) {
                        items(items = content.entries, key = { entry -> entry.key }) { entry ->
                            OutlinedTextField(
                                value = entry.value,
                                onValueChange = { newValue -> onValueChange(entry.key, newValue) },
                                label = { Text(text = entry.key) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    TextButton(
                        onClick = onSave,
                        enabled = content.hasPendingChanges && !content.isSaving,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        if (content.isSaving) {
                            Text(text = stringResource(id = R.string.storage_config_map_saving))
                        } else {
                            Text(text = stringResource(id = R.string.storage_config_map_save))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretDetailRoute(
    viewModel: SecretDetailViewModel,
    biometricHelper: BiometricHelper = BiometricHelper(),
    onBackClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    SecretDetailScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onBackClick = onBackClick,
        onRevealClick = { key ->
            coroutineScope.launch {
                val hostActivity = activity ?: return@launch
                val authResult = biometricHelper.authenticate(
                    activity = hostActivity,
                    promptConfig = BiometricHelper.PromptConfig(
                        title = context.getString(R.string.storage_secret_biometric_prompt_title),
                        subtitle = context.getString(R.string.storage_secret_biometric_subtitle),
                        cancelText = context.getString(R.string.storage_secret_biometric_cancel),
                    ),
                )
                if (authResult is BiometricHelper.AuthResult.Success) {
                    withContext(Dispatchers.Main) {
                        viewModel.revealValue(
                            key = key,
                            biometricToken = BiometricToken.fromAuthProof(
                                proof = UUID.randomUUID().toString(),
                                validForMillis = 30_000L,
                            ),
                        )
                    }
                }
            }
        },
        onHideClick = viewModel::hideValue,
        onCopyClick = { value -> copyToClipboard(clipboardManager, value) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretDetailScreen(
    state: SecretDetailUiState,
    onRefresh: () -> Unit,
    onBackClick: () -> Unit,
    onRevealClick: (key: String) -> Unit,
    onHideClick: (key: String) -> Unit,
    onCopyClick: (value: String) -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.storage_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.storage_section_spacing)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = state.name) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(id = R.string.storage_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.storage_refresh_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val content = state.contentState) {
            SecretDetailContentState.Loading -> CenteredLoading(modifier = Modifier.padding(innerPadding))
            is SecretDetailContentState.Error -> CenteredError(
                message = content.cause?.localizedMessage ?: stringResource(id = R.string.storage_load_error),
                modifier = Modifier.padding(innerPadding),
            )

            is SecretDetailContentState.Success -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = screenPadding),
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                ) {
                    item {
                        Text(
                            text = stringResource(id = R.string.storage_detail_namespace, content.namespace),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item {
                        Text(
                            text = stringResource(id = R.string.storage_secret_type, content.type),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    items(items = content.keys, key = { it }) { key ->
                        SecretValueRow(
                            keyName = key,
                            value = content.revealedValues[key],
                            remainingSeconds = content.remainingRevealSeconds[key],
                            isRevealing = content.revealingKeys.contains(key),
                            onRevealClick = { onRevealClick(key) },
                            onHideClick = { onHideClick(key) },
                            onCopyClick = onCopyClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretValueRow(
    keyName: String,
    value: String?,
    remainingSeconds: Int?,
    isRevealing: Boolean,
    onRevealClick: () -> Unit,
    onHideClick: () -> Unit,
    onCopyClick: (value: String) -> Unit,
) {
    val itemPadding = dimensionResource(id = R.dimen.storage_item_padding)
    val rowSpacing = dimensionResource(id = R.dimen.storage_row_spacing)
    val revealed = value != null
    val displayedValue = value ?: stringResource(id = R.string.storage_secret_value_masked)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Text(text = keyName, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionContainer(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = displayedValue, style = MaterialTheme.typography.bodyMedium)
                }
                if (revealed) {
                    IconButton(onClick = { onCopyClick(displayedValue) }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(id = R.string.storage_secret_copy_cd),
                        )
                    }
                }
            }
            if (revealed) {
                Text(
                    text = stringResource(
                        id = R.string.storage_secret_auto_hide_countdown,
                        remainingSeconds ?: 0,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = if (revealed) onHideClick else onRevealClick,
                    enabled = !isRevealing,
                ) {
                    Text(
                        text = if (revealed) {
                            stringResource(id = R.string.storage_secret_hide)
                        } else {
                            stringResource(id = R.string.storage_secret_reveal)
                        },
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(clipboardManager: ClipboardManager, value: String) {
    clipboardManager.setText(AnnotatedString(value))
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

@Composable
private fun EventItem(
    event: ClusterEvent,
) {
    val itemPadding = dimensionResource(id = R.dimen.storage_item_padding)
    val rowSpacing = dimensionResource(id = R.dimen.storage_row_spacing)

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Text(
                text = event.reason,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = event.message,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    id = R.string.storage_event_type,
                    if (event.type == ClusterEventType.Warning) {
                        stringResource(id = R.string.storage_event_warning)
                    } else {
                        stringResource(id = R.string.storage_event_normal)
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (event.type == ClusterEventType.Warning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = stringResource(
                    id = R.string.storage_event_last_timestamp,
                    event.lastTimestamp.atZone(ZoneId.systemDefault()).format(EVENT_TIME_FORMATTER),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(text = message, color = MaterialTheme.colorScheme.error)
    }
}

private val EVENT_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
