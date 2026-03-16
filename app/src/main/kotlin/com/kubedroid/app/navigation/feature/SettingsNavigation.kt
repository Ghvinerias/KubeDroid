package com.kubedroid.app.navigation.feature

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.kubedroid.app.R
import com.kubedroid.app.navigation.Routes
import com.kubedroid.app.resources.ManifestDeployViewModel
import com.kubedroid.feature.onboarding.R as OnboardingR
import com.kubedroid.feature.onboarding.ui.CoachMarkItem
import com.kubedroid.feature.onboarding.ui.CoachMarkOverlay
import com.kubedroid.feature.onboarding.ui.CoachMarkPlacement
import com.kubedroid.feature.onboarding.ui.CoachMarksViewModel
import com.kubedroid.feature.settings.ui.AddClusterScreen
import com.kubedroid.feature.settings.ui.ClusterSettingsViewModel
import com.kubedroid.feature.settings.ui.DashboardScreen
import com.kubedroid.feature.settings.ui.DashboardViewModel
import com.kubedroid.feature.settings.ui.SettingsRoute
import com.kubedroid.feature.settings.ui.SettingsViewModel
import com.kubedroid.feature.resources.detail.YAMLEditorScreen

fun NavGraphBuilder.settingsGraph(navController: NavController) {
    val hostController = navController as NavHostController

    composable(Routes.Dashboard.root) {
        val settingsViewModel: ClusterSettingsViewModel = hiltViewModel()
        val dashboardViewModel: DashboardViewModel = hiltViewModel()
        val coachMarksViewModel: CoachMarksViewModel = hiltViewModel()
        val uiState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
        val isRefreshing by dashboardViewModel.isRefreshing.collectAsStateWithLifecycle()
        val coachMarkUiState by coachMarksViewModel.state.collectAsStateWithLifecycle()
        var coachMarkIndex by rememberSaveable { mutableStateOf(0) }

        val coachMarks = listOf(
            CoachMarkItem(
                title = stringResource(OnboardingR.string.coach_mark_dashboard_title),
                description = stringResource(OnboardingR.string.coach_mark_dashboard_description),
                placement = CoachMarkPlacement.Top,
            ),
            CoachMarkItem(
                title = stringResource(OnboardingR.string.coach_mark_add_cluster_title),
                description = stringResource(OnboardingR.string.coach_mark_add_cluster_description),
                placement = CoachMarkPlacement.Center,
            ),
            CoachMarkItem(
                title = stringResource(OnboardingR.string.coach_mark_bottom_nav_title),
                description = stringResource(OnboardingR.string.coach_mark_bottom_nav_description),
                placement = CoachMarkPlacement.Bottom,
            ),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            DashboardScreen(
                state = uiState,
                isRefreshing = isRefreshing,
                onRefresh = dashboardViewModel::refresh,
                onClusterClick = { contextName ->
                    settingsViewModel.connectSelectedCluster(contextName) {
                        hostController.navigate(Routes.Pods.list) { launchSingleTop = true }
                    }
                },
                onAddClusterClick = {
                    hostController.navigate(Routes.AddCluster.root) {
                        launchSingleTop = true
                    }
                },
            )

            if (!coachMarkUiState.isLoading && coachMarkUiState.shouldShow && coachMarkIndex < coachMarks.size) {
                val isLast = coachMarkIndex == coachMarks.lastIndex
                CoachMarkOverlay(
                    item = coachMarks[coachMarkIndex],
                    isLast = isLast,
                    onNext = {
                        if (isLast) {
                            coachMarksViewModel.completeCoachMarks()
                        } else {
                            coachMarkIndex += 1
                        }
                    },
                )
            }
        }
    }

    composable(Routes.AddCluster.root) {
        val parentEntry = hostController.getBackStackEntry(Routes.Dashboard.root)
        val viewModel: ClusterSettingsViewModel = hiltViewModel(parentEntry)
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        AddClusterScreen(
            inputMode = state.inputMode,
            kubeConfigText = state.kubeConfigText,
            server = state.server,
            token = state.token,
            insecureTlsEnabled = state.insecureTlsEnabled,
            showInsecureTlsWarningDialog = state.showInsecureTlsWarningDialog,
            connectionState = state.connectionState,
            onInputModeChange = viewModel::onInputModeChange,
            onKubeConfigTextChange = viewModel::onKubeConfigTextChange,
            onServerChange = viewModel::onServerChange,
            onTokenChange = viewModel::onTokenChange,
            onInsecureTlsToggleRequested = viewModel::onInsecureTlsToggleRequested,
            onConfirmEnableInsecureTls = viewModel::confirmEnableInsecureTls,
            onDismissInsecureTlsWarning = viewModel::dismissInsecureTlsWarning,
            onSaveClick = {
                viewModel.saveCluster(reconnect = false) {
                    hostController.navigate(Routes.Dashboard.root) {
                        popUpTo(hostController.graph.findStartDestination().id) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                }
            },
            onCancelClick = { hostController.popBackStack() },
            onConnectClick = {
                viewModel.saveCluster(reconnect = true) {
                    hostController.navigate(Routes.Dashboard.root) {
                        popUpTo(hostController.graph.findStartDestination().id) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                }
            },
        )
    }

    composable(Routes.Settings.root) {
        val viewModel: SettingsViewModel = hiltViewModel()
        SettingsRoute(
            viewModel = viewModel,
            onExportLogsClick = {},
            onOpenSourceLicensesClick = {},
            onDeployManifestClick = { hostController.navigate(Routes.Settings.deployManifest) },
        )
    }

    composable(Routes.Settings.deployManifest) {
        val viewModel: ManifestDeployViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        YAMLEditorScreen(
            title = stringResource(id = R.string.deploy_manifest_title),
            applyActionLabel = stringResource(id = R.string.deploy_manifest_action),
            yamlDraft = state.yamlDraft,
            isApplying = state.isApplying,
            lastYamlEditResult = state.lastYamlEditResult,
            applyError = state.applyError,
            onYamlDraftChange = viewModel::updateYamlDraft,
            onApplyClick = viewModel::applyYaml,
            onBackClick = { hostController.popBackStack() },
        )
    }
}

@Composable
fun DashboardPreviewContent() {
    DashboardScreen(
        state = com.kubedroid.feature.settings.ui.DashboardUiState.Empty,
        isRefreshing = false,
        onRefresh = {},
        onClusterClick = {},
        onAddClusterClick = {},
    )
}
