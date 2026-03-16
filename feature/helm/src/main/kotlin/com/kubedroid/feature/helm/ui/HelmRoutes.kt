package com.kubedroid.feature.helm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.feature.helm.R
import com.kubedroid.feature.helm.model.HelmRelease
import com.kubedroid.feature.helm.model.HelmRevision

@Composable
fun HelmRoute(
    viewModel: HelmViewModel,
    useTwoPane: Boolean = false,
    hingePadding: Dp = 0.dp,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedNamespace by viewModel.selectedNamespace.collectAsStateWithLifecycle()
    val selectedRelease = state.selectedRelease
    val releases = (state.listState as? HelmUiState.Success)?.releases.orEmpty()

    if (useTwoPane) {
        HelmListScreen(
            releases = releases.map { it.toUiModel() },
            selectedNamespace = selectedNamespace,
            namespaces = state.availableNamespaces,
            onNamespaceSelected = viewModel::selectNamespace,
            onReleaseClick = { selected ->
                releases.firstOrNull { it.name == selected.name }?.let(viewModel::openRelease)
            },
            useTwoPane = true,
            hingePadding = hingePadding,
            detailPane = {
                val detailRelease = selectedRelease
                if (detailRelease == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Text(
                            text = stringResource(id = R.string.helm_two_pane_select_release),
                        )
                    }
                } else {
                    HelmDetailScreen(
                        releaseName = detailRelease.name,
                        selectedTab = state.selectedTab,
                        infoItems = listOf(
                            stringResource(id = R.string.helm_detail_info_namespace) to detailRelease.namespace,
                            stringResource(id = R.string.helm_detail_info_chart) to detailRelease.chart,
                            stringResource(id = R.string.helm_detail_info_chart_version) to detailRelease.chartVersion,
                            stringResource(id = R.string.helm_detail_info_app_version) to detailRelease.appVersion,
                            stringResource(id = R.string.helm_detail_info_status) to detailRelease.status,
                        ),
                        revisions = state.revisions.map { it.toUiModel() },
                        manifest = state.manifest,
                        onTabSelected = viewModel::setSelectedTab,
                    )
                }
            },
        )
        return
    }

    if (selectedRelease == null) {
        HelmListScreen(
            releases = releases.map { it.toUiModel() },
            selectedNamespace = selectedNamespace,
            namespaces = state.availableNamespaces,
            onNamespaceSelected = viewModel::selectNamespace,
            onReleaseClick = { selected ->
                releases.firstOrNull { it.name == selected.name }?.let(viewModel::openRelease)
            },
        )
    } else {
        val infoItems = listOf(
            stringResource(id = R.string.helm_detail_info_namespace) to selectedRelease.namespace,
            stringResource(id = R.string.helm_detail_info_chart) to selectedRelease.chart,
            stringResource(id = R.string.helm_detail_info_chart_version) to selectedRelease.chartVersion,
            stringResource(id = R.string.helm_detail_info_app_version) to selectedRelease.appVersion,
            stringResource(id = R.string.helm_detail_info_status) to selectedRelease.status,
        )

        HelmDetailScreen(
            releaseName = selectedRelease.name,
            selectedTab = state.selectedTab,
            infoItems = infoItems,
            revisions = state.revisions.map { it.toUiModel() },
            manifest = state.manifest,
            onTabSelected = viewModel::setSelectedTab,
        )
    }
}

private fun HelmRelease.toUiModel(): HelmReleaseUiModel {
    return HelmReleaseUiModel(
        name = name,
        chart = chart,
        version = chartVersion,
        status = status.toUiStatus(),
        lastDeployedEpochMillis = lastDeployed,
    )
}

private fun HelmRevision.toUiModel(): HelmRevisionUiModel {
    return HelmRevisionUiModel(
        revision = revision,
        status = status.toUiStatus(),
        description = description,
        deployedAtEpochMillis = updatedAt,
    )
}

private fun String.toUiStatus(): HelmReleaseStatus {
    return when (lowercase()) {
        "deployed" -> HelmReleaseStatus.DEPLOYED
        "failed" -> HelmReleaseStatus.FAILED
        "pending",
        "pending_install",
        "pending_upgrade",
        "pending_rollback",
        -> HelmReleaseStatus.PENDING
        "superseded" -> HelmReleaseStatus.SUPERSEDED
        "uninstalled" -> HelmReleaseStatus.UNINSTALLED
        "uninstalling" -> HelmReleaseStatus.UNINSTALLING
        else -> HelmReleaseStatus.UNKNOWN
    }
}
