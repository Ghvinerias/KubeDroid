package com.kubedroid.feature.crd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.feature.crd.R
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import com.kubedroid.feature.pods.resources.ResourceListItem
import com.kubedroid.feature.pods.resources.ResourceListUiState
import com.kubedroid.feature.pods.resources.ResourceRow

@Composable
fun CrdResourceListRoute(
    crd: CustomResourceDefinition,
    viewModel: CrdResourceListViewModel,
    onResourceClick: (ResourceListItem) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(crd.key()) {
        viewModel.onIntent(CrdResourceListIntent.BindCrd(crd))
    }

    CrdResourceListScreen(
        state = state,
        onRetryClick = { viewModel.onIntent(CrdResourceListIntent.Retry) },
        onNamespaceClick = { viewModel.onIntent(CrdResourceListIntent.OpenNamespacePicker) },
        onNamespaceDismiss = { viewModel.onIntent(CrdResourceListIntent.DismissNamespacePicker) },
        onNamespaceSelected = { viewModel.onIntent(CrdResourceListIntent.SelectNamespace(it)) },
        onResourceClick = onResourceClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrdResourceListScreen(
    state: CrdResourceListScreenState,
    onRetryClick: () -> Unit,
    onNamespaceClick: () -> Unit,
    onNamespaceDismiss: () -> Unit,
    onNamespaceSelected: (String) -> Unit,
    onResourceClick: (ResourceListItem) -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.crd_screen_padding)
    val rowPadding = dimensionResource(id = R.dimen.crd_row_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.crd_section_spacing)
    val selectedCrd = state.selectedCrd

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedCrd == null) {
                            stringResource(id = R.string.crd_resources_title)
                        } else {
                            stringResource(id = R.string.crd_resources_title_format, selectedCrd.kind)
                        },
                    )
                },
                actions = {
                    IconButton(onClick = onRetryClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.crd_resources_refresh_cd),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = screenPadding),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            if (selectedCrd != null && !selectedCrd.isClusterScoped()) {
                TextButton(onClick = onNamespaceClick) {
                    Text(text = state.selectedNamespace)
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when (val listState = state.listState) {
                    ResourceListUiState.Loading -> CircularProgressIndicator()
                    ResourceListUiState.Empty -> Text(text = stringResource(id = R.string.crd_resources_empty))
                    is ResourceListUiState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                        ) {
                            Text(
                                text = listState.cause?.localizedMessage
                                    ?: stringResource(id = R.string.crd_resources_error),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRetryClick) {
                                Text(text = stringResource(id = R.string.crd_resources_retry))
                            }
                        }
                    }

                    is ResourceListUiState.Success -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = listState.items,
                                key = { it.id },
                            ) { item ->
                                ResourceRow(
                                    item = item,
                                    onClick = { onResourceClick(item) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = rowPadding),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.isNamespacePickerVisible) {
        CrdNamespacePickerBottomSheet(
            namespaces = state.namespaces,
            selectedNamespace = state.selectedNamespace,
            onNamespaceSelected = onNamespaceSelected,
            onDismissRequest = onNamespaceDismiss,
        )
    }
}

private fun CustomResourceDefinition.isClusterScoped(): Boolean =
    scope.equals("Cluster", ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrdNamespacePickerBottomSheet(
    namespaces: List<String>,
    selectedNamespace: String,
    onNamespaceSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sheetPadding = dimensionResource(id = R.dimen.crd_screen_padding)

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Text(
            text = stringResource(id = R.string.crd_resources_namespace_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(sheetPadding),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(namespaces, key = { it }) { namespace ->
                val selected = namespace == selectedNamespace
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onNamespaceSelected(namespace) },
                ) {
                    Text(
                        text = namespace,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
