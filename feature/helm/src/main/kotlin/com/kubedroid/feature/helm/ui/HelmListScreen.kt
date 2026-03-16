package com.kubedroid.feature.helm.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
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
import com.kubedroid.core.ui.namespace.NamespaceSelector
import com.kubedroid.feature.helm.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HelmListScreen(
    releases: List<HelmReleaseUiModel>,
    selectedNamespace: String,
    namespaces: List<String>,
    onNamespaceSelected: (String) -> Unit,
    onReleaseClick: (HelmReleaseUiModel) -> Unit,
    useTwoPane: Boolean = false,
    detailPane: (@Composable () -> Unit)? = null,
    hingePadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val screenPadding = dimensionResource(id = R.dimen.helm_screen_padding)
    val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<Unit>()
    val scope = rememberCoroutineScope()
    var isNamespaceSheetVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.helm_list_title)) },
            )
        },
    ) { paddingValues ->
        if (useTwoPane && detailPane != null) {
            ListDetailPaneScaffold(
                directive = scaffoldNavigator.scaffoldDirective,
                value = scaffoldNavigator.scaffoldValue,
                listPane = {
                    AnimatedPane(modifier = Modifier.padding(end = hingePadding)) {
                        HelmListPane(
                            releases = releases,
                            selectedNamespace = selectedNamespace,
                            namespaces = namespaces,
                            onNamespaceSelected = onNamespaceSelected,
                            isNamespaceSheetVisible = isNamespaceSheetVisible,
                            onNamespaceSheetVisibilityChange = { isNamespaceSheetVisible = it },
                            screenPadding = screenPadding,
                            onReleaseClick = { release ->
                                onReleaseClick(release)
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
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        } else {
            HelmListPane(
                releases = releases,
                selectedNamespace = selectedNamespace,
                namespaces = namespaces,
                onNamespaceSelected = onNamespaceSelected,
                isNamespaceSheetVisible = isNamespaceSheetVisible,
                onNamespaceSheetVisibilityChange = { isNamespaceSheetVisible = it },
                screenPadding = screenPadding,
                onReleaseClick = onReleaseClick,
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        }
    }
}

@Composable
private fun HelmListPane(
    releases: List<HelmReleaseUiModel>,
    selectedNamespace: String,
    namespaces: List<String>,
    onNamespaceSelected: (String) -> Unit,
    isNamespaceSheetVisible: Boolean,
    onNamespaceSheetVisibilityChange: (Boolean) -> Unit,
    screenPadding: androidx.compose.ui.unit.Dp,
    onReleaseClick: (HelmReleaseUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = screenPadding),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.helm_content_spacing)),
    ) {
        NamespaceSelector(
            namespaces = namespaces,
            selectedNamespace = selectedNamespace.ifBlank { null },
            onNamespaceSelected = { namespace ->
                namespace?.let(onNamespaceSelected)
            },
            isSheetVisible = isNamespaceSheetVisible,
            onSheetVisibilityChange = onNamespaceSheetVisibilityChange,
            allowAllNamespaces = false,
        )

        if (releases.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(id = R.string.helm_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items = releases, key = { it.name }) { release ->
                HelmReleaseRow(
                    release = release,
                    onClick = { onReleaseClick(release) },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HelmReleaseRow(
    release: HelmReleaseUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowPadding = dimensionResource(id = R.dimen.helm_list_item_padding)
    val contentSpacing = dimensionResource(id = R.dimen.helm_content_spacing)
    val unknownText = stringResource(id = R.string.helm_value_unknown)
    val lastDeployedText = release.lastDeployedEpochMillis?.toUserDateTime() ?: unknownText

    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = rowPadding),
        verticalArrangement = Arrangement.spacedBy(contentSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = release.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            HelmStatusBadge(status = release.status)
        }

        Text(
            text = stringResource(id = R.string.helm_release_chart_value, release.chart),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(id = R.string.helm_release_version_value, release.version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(id = R.string.helm_release_last_deployed_value, lastDeployedText),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HelmStatusBadge(
    status: HelmReleaseStatus,
) {
    val horizontalPadding = dimensionResource(id = R.dimen.helm_badge_horizontal_padding)
    val verticalPadding = dimensionResource(id = R.dimen.helm_badge_vertical_padding)
    val colors = MaterialTheme.colorScheme
    val containerColor = when (status) {
        HelmReleaseStatus.DEPLOYED -> colors.primaryContainer
        HelmReleaseStatus.FAILED -> colors.errorContainer
        HelmReleaseStatus.PENDING -> colors.secondaryContainer
        HelmReleaseStatus.SUPERSEDED -> colors.tertiaryContainer
        HelmReleaseStatus.UNINSTALLED,
        HelmReleaseStatus.UNINSTALLING,
        HelmReleaseStatus.UNKNOWN,
        -> colors.surfaceVariant
    }
    val contentColor = when (status) {
        HelmReleaseStatus.DEPLOYED -> colors.onPrimaryContainer
        HelmReleaseStatus.FAILED -> colors.onErrorContainer
        HelmReleaseStatus.PENDING -> colors.onSecondaryContainer
        HelmReleaseStatus.SUPERSEDED -> colors.onTertiaryContainer
        HelmReleaseStatus.UNINSTALLED,
        HelmReleaseStatus.UNINSTALLING,
        HelmReleaseStatus.UNKNOWN,
        -> colors.onSurfaceVariant
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(id = status.labelResId),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(
                horizontal = horizontalPadding,
                vertical = verticalPadding,
            ),
        )
    }
}
