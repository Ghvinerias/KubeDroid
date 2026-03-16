package com.kubedroid.feature.deployments.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.kubedroid.core.network.deployments.Deployment
import com.kubedroid.feature.deployments.R

@Composable
fun DeploymentDetailRoute(
    namespace: String,
    deploymentName: String,
    viewModel: DeploymentDetailViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(namespace, deploymentName) {
        viewModel.onIntent(
            DeploymentDetailIntent.Load(
                namespace = namespace,
                deploymentName = deploymentName,
            ),
        )
    }

    DeploymentDetailScreen(
        state = uiState,
        onBackClick = onBackClick,
        onTabSelected = { viewModel.onIntent(DeploymentDetailIntent.SelectTab(it)) },
        onScaleClick = { viewModel.onIntent(DeploymentDetailIntent.ShowScaleDialog) },
        onScaleDismiss = { viewModel.onIntent(DeploymentDetailIntent.DismissScaleDialog) },
        onScaleDecrease = { viewModel.onIntent(DeploymentDetailIntent.DecreaseScale) },
        onScaleIncrease = { viewModel.onIntent(DeploymentDetailIntent.IncreaseScale) },
        onScaleConfirm = { viewModel.onIntent(DeploymentDetailIntent.ConfirmScale) },
        onPauseResumeClick = { viewModel.onIntent(DeploymentDetailIntent.TogglePauseResume) },
        onRetryHistory = { viewModel.onIntent(DeploymentDetailIntent.RetryRolloutHistory) },
        onRollback = { viewModel.onIntent(DeploymentDetailIntent.RollbackToRevision(it)) },
        onConsumeActionMessage = { viewModel.onIntent(DeploymentDetailIntent.ConsumeActionMessage) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploymentDetailScreen(
    state: DeploymentDetailUiState,
    onBackClick: () -> Unit,
    onTabSelected: (DeploymentDetailTab) -> Unit,
    onScaleClick: () -> Unit,
    onScaleDismiss: () -> Unit,
    onScaleDecrease: () -> Unit,
    onScaleIncrease: () -> Unit,
    onScaleConfirm: () -> Unit,
    onPauseResumeClick: () -> Unit,
    onRetryHistory: () -> Unit,
    onRollback: (Long?) -> Unit,
    onConsumeActionMessage: () -> Unit,
) {
    val contentPadding = dimensionResource(id = R.dimen.deployment_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.deployment_content_spacing)
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
                    Text(
                        text = stringResource(
                            id = R.string.deployment_detail_title,
                            state.deploymentName,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.deployment_detail_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onPauseResumeClick, enabled = !state.isActionInProgress) {
                        Icon(
                            imageVector = if (state.isRolloutPaused) {
                                Icons.Default.PlayArrow
                            } else {
                                Icons.Default.Pause
                            },
                            contentDescription = stringResource(
                                id = if (state.isRolloutPaused) {
                                    R.string.deployment_detail_resume_cd
                                } else {
                                    R.string.deployment_detail_pause_cd
                                },
                            ),
                        )
                    }
                    IconButton(onClick = onScaleClick, enabled = state.deployment != null) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(id = R.string.deployment_detail_scale_cd),
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
                .padding(horizontal = contentPadding),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            TabRow(
                selectedTabIndex = when (state.selectedTab) {
                    DeploymentDetailTab.OVERVIEW -> 0
                    DeploymentDetailTab.ROLLOUT_HISTORY -> 1
                },
            ) {
                Tab(
                    selected = state.selectedTab == DeploymentDetailTab.OVERVIEW,
                    onClick = { onTabSelected(DeploymentDetailTab.OVERVIEW) },
                    text = { Text(text = stringResource(id = R.string.deployment_detail_tab_overview)) },
                )
                Tab(
                    selected = state.selectedTab == DeploymentDetailTab.ROLLOUT_HISTORY,
                    onClick = { onTabSelected(DeploymentDetailTab.ROLLOUT_HISTORY) },
                    text = { Text(text = stringResource(id = R.string.deployment_detail_tab_rollout_history)) },
                )
            }

            when (state.selectedTab) {
                DeploymentDetailTab.OVERVIEW -> {
                    DeploymentOverviewContent(
                        deployment = state.deployment,
                        onScaleClick = onScaleClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                DeploymentDetailTab.ROLLOUT_HISTORY -> {
                    RolloutHistoryScreen(
                        state = state.rolloutHistoryState,
                        onRetryClick = onRetryHistory,
                        onRollbackClick = onRollback,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (state.isScaleDialogVisible) {
        ScaleDialog(
            replicas = state.scaleReplicas,
            onDismissRequest = onScaleDismiss,
            onDecreaseClick = onScaleDecrease,
            onIncreaseClick = onScaleIncrease,
            onConfirmClick = onScaleConfirm,
        )
    }
}

@Composable
private fun DeploymentOverviewContent(
    deployment: Deployment?,
    onScaleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sectionSpacing = dimensionResource(id = R.dimen.deployment_content_spacing)

    if (deployment == null) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        DeploymentStatRow(
            label = stringResource(id = R.string.deployment_detail_desired_replicas),
            value = deployment.desiredReplicas.toString(),
        )
        DeploymentStatRow(
            label = stringResource(id = R.string.deployment_detail_ready_replicas),
            value = deployment.readyReplicas.toString(),
        )
        DeploymentStatRow(
            label = stringResource(id = R.string.deployment_detail_updated_replicas),
            value = deployment.updatedReplicas.toString(),
        )
        DeploymentStatRow(
            label = stringResource(id = R.string.deployment_detail_available_replicas),
            value = deployment.availableReplicas.toString(),
        )
        DeploymentStatRow(
            label = stringResource(id = R.string.deployment_detail_observed_generation),
            value = deployment.observedGeneration?.toString()
                ?: stringResource(id = R.string.deployment_detail_unknown_value),
        )
        androidx.compose.material3.Button(onClick = onScaleClick) {
            Text(text = stringResource(id = R.string.deployment_detail_scale_button))
        }
    }
}

@Composable
private fun DeploymentStatRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label)
        Text(text = value)
    }
}

@Composable
private fun DeploymentActionMessage.toMessageText(): String {
    return when (this) {
        is DeploymentActionMessage.Success -> {
            when (action) {
                DeploymentUserAction.SCALE -> stringResource(id = R.string.deployment_action_scale_success)
                DeploymentUserAction.PAUSE -> stringResource(id = R.string.deployment_action_pause_success)
                DeploymentUserAction.RESUME -> stringResource(id = R.string.deployment_action_resume_success)
                DeploymentUserAction.ROLLBACK -> stringResource(id = R.string.deployment_action_rollback_success)
            }
        }

        is DeploymentActionMessage.Failure -> {
            when (action) {
                DeploymentUserAction.SCALE -> scaleFailureText(error = error)
                DeploymentUserAction.PAUSE -> pauseResumeFailureText(
                    action = DeploymentUserAction.PAUSE,
                    error = error,
                )

                DeploymentUserAction.RESUME -> pauseResumeFailureText(
                    action = DeploymentUserAction.RESUME,
                    error = error,
                )

                DeploymentUserAction.ROLLBACK -> rollbackFailureText(error = error)
            }
        }
    }
}

@Composable
private fun scaleFailureText(error: DeploymentActionError): String {
    return when (error) {
        DeploymentActionError.INVALID_REPLICA_COUNT -> stringResource(
            id = R.string.deployment_action_scale_invalid_replicas,
        )

        DeploymentActionError.NOT_FOUND -> stringResource(id = R.string.deployment_action_scale_not_found)
        DeploymentActionError.FORBIDDEN -> stringResource(id = R.string.deployment_action_scale_forbidden)
        DeploymentActionError.CONFLICT -> stringResource(id = R.string.deployment_action_scale_conflict)
        DeploymentActionError.UNKNOWN -> stringResource(id = R.string.deployment_action_generic_failure)
    }
}

@Composable
private fun pauseResumeFailureText(
    action: DeploymentUserAction,
    error: DeploymentActionError,
): String {
    if (error == DeploymentActionError.UNKNOWN) {
        return stringResource(id = R.string.deployment_action_generic_failure)
    }

    return when (action) {
        DeploymentUserAction.PAUSE -> when (error) {
            DeploymentActionError.NOT_FOUND -> stringResource(id = R.string.deployment_action_pause_not_found)
            DeploymentActionError.FORBIDDEN -> stringResource(id = R.string.deployment_action_pause_forbidden)
            DeploymentActionError.CONFLICT -> stringResource(id = R.string.deployment_action_pause_conflict)
            DeploymentActionError.INVALID_REPLICA_COUNT,
            DeploymentActionError.UNKNOWN,
            -> stringResource(id = R.string.deployment_action_generic_failure)
        }

        DeploymentUserAction.RESUME -> when (error) {
            DeploymentActionError.NOT_FOUND -> stringResource(id = R.string.deployment_action_resume_not_found)
            DeploymentActionError.FORBIDDEN -> stringResource(id = R.string.deployment_action_resume_forbidden)
            DeploymentActionError.CONFLICT -> stringResource(id = R.string.deployment_action_resume_conflict)
            DeploymentActionError.INVALID_REPLICA_COUNT,
            DeploymentActionError.UNKNOWN,
            -> stringResource(id = R.string.deployment_action_generic_failure)
        }

        DeploymentUserAction.SCALE,
        DeploymentUserAction.ROLLBACK,
        -> stringResource(id = R.string.deployment_action_generic_failure)
    }
}

@Composable
private fun rollbackFailureText(error: DeploymentActionError): String {
    return when (error) {
        DeploymentActionError.NOT_FOUND -> stringResource(id = R.string.deployment_action_rollback_not_found)
        DeploymentActionError.FORBIDDEN -> stringResource(id = R.string.deployment_action_rollback_forbidden)
        DeploymentActionError.CONFLICT -> stringResource(id = R.string.deployment_action_rollback_conflict)
        DeploymentActionError.INVALID_REPLICA_COUNT,
        DeploymentActionError.UNKNOWN,
        -> stringResource(id = R.string.deployment_action_generic_failure)
    }
}
