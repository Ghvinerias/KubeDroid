package com.kubedroid.feature.crd.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.feature.crd.R
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import com.kubedroid.feature.pods.resources.ResourceListUiState

@Composable
fun CrdListRoute(
    viewModel: CrdListViewModel,
    onCrdClick: (CustomResourceDefinition) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.onIntent(CrdListIntent.Load)
    }

    CrdListScreen(
        state = state,
        onQueryChanged = { viewModel.onIntent(CrdListIntent.SearchQueryChanged(it)) },
        onRetryClick = { viewModel.onIntent(CrdListIntent.Retry) },
        onToggleFavourite = { crd, isFavourite ->
            viewModel.onIntent(CrdListIntent.ToggleFavourite(crd = crd, favourite = isFavourite))
        },
        onCrdClick = onCrdClick,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CrdListScreen(
    state: CrdListScreenState,
    onQueryChanged: (String) -> Unit,
    onRetryClick: () -> Unit,
    onToggleFavourite: (CustomResourceDefinition, Boolean) -> Unit,
    onCrdClick: (CustomResourceDefinition) -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.crd_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.crd_section_spacing)
    val rowPadding = dimensionResource(id = R.dimen.crd_row_padding)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.crd_list_title)) },
                actions = {
                    IconButton(onClick = onRetryClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.crd_list_refresh_cd),
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
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (state.searchQuery.isNotBlank()) {
                        IconButton(onClick = { onQueryChanged("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(id = R.string.crd_list_clear_search_cd),
                            )
                        }
                    }
                },
                placeholder = {
                    Text(text = stringResource(id = R.string.crd_list_search_placeholder))
                },
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when (val listState = state.listState) {
                    ResourceListUiState.Loading -> CircularProgressIndicator()
                    ResourceListUiState.Empty -> Text(text = stringResource(id = R.string.crd_list_empty))
                    is ResourceListUiState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                        ) {
                            Text(
                                text = listState.cause?.localizedMessage
                                    ?: stringResource(id = R.string.crd_list_error),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRetryClick) {
                                Text(text = stringResource(id = R.string.crd_list_retry))
                            }
                        }
                    }

                    is ResourceListUiState.Success -> {
                        val favourites = listState.items.filter { it.isFavourite }
                        val others = listState.items.filterNot { it.isFavourite }

                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (favourites.isNotEmpty()) {
                                stickyHeader {
                                    SectionHeader(
                                        title = stringResource(id = R.string.crd_list_favourites_header),
                                    )
                                }
                                items(
                                    items = favourites,
                                    key = { it.definition.key() },
                                ) { item ->
                                    CrdRow(
                                        item = item,
                                        onClick = { onCrdClick(item.definition) },
                                        onToggleFavourite = {
                                            onToggleFavourite(item.definition, !item.isFavourite)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = rowPadding),
                                    )
                                }
                            }

                            stickyHeader {
                                SectionHeader(
                                    title = stringResource(id = R.string.crd_list_all_header),
                                )
                            }
                            items(
                                items = others,
                                key = { it.definition.key() },
                            ) { item ->
                                CrdRow(
                                    item = item,
                                    onClick = { onCrdClick(item.definition) },
                                    onToggleFavourite = {
                                        onToggleFavourite(item.definition, !item.isFavourite)
                                    },
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
}

@Composable
private fun SectionHeader(
    title: String,
) {
    val headerPadding = dimensionResource(id = R.dimen.crd_section_header_padding)

    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = headerPadding),
    )
}

@Composable
private fun CrdRow(
    item: CrdListItem,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.definition.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    id = R.string.crd_list_item_subtitle,
                    item.definition.kind,
                    item.definition.scope,
                    item.definition.group,
                    item.definition.version,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (item.isFavourite) Icons.Default.Star else Icons.Outlined.StarBorder,
                contentDescription = if (item.isFavourite) {
                    stringResource(id = R.string.crd_list_unfavourite_cd)
                } else {
                    stringResource(id = R.string.crd_list_favourite_cd)
                },
            )
        }
    }
}
