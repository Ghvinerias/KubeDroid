package com.kubedroid.feature.deployments.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.deployments.RolloutResult
import com.kubedroid.core.network.deployments.ScaleResult
import com.kubedroid.feature.deployments.DeploymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DeploymentDetailIntent {
    data class Load(
        val namespace: String,
        val deploymentName: String,
    ) : DeploymentDetailIntent

    data class SelectTab(val tab: DeploymentDetailTab) : DeploymentDetailIntent
    data object ShowScaleDialog : DeploymentDetailIntent
    data object DismissScaleDialog : DeploymentDetailIntent
    data object IncreaseScale : DeploymentDetailIntent
    data object DecreaseScale : DeploymentDetailIntent
    data object ConfirmScale : DeploymentDetailIntent
    data object TogglePauseResume : DeploymentDetailIntent
    data class RollbackToRevision(val revision: Long?) : DeploymentDetailIntent
    data object RetryRolloutHistory : DeploymentDetailIntent
    data object ConsumeActionMessage : DeploymentDetailIntent
}

@HiltViewModel
class DeploymentDetailViewModel @Inject constructor(
    private val repository: DeploymentRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "DeploymentDetailVM"
    }

    private val _uiState = MutableStateFlow(DeploymentDetailUiState())
    val uiState: StateFlow<DeploymentDetailUiState> = _uiState.asStateFlow()

    fun onIntent(intent: DeploymentDetailIntent) {
        when (intent) {
            is DeploymentDetailIntent.Load -> load(
                namespace = intent.namespace,
                deploymentName = intent.deploymentName,
            )

            is DeploymentDetailIntent.SelectTab -> {
                _uiState.update { it.copy(selectedTab = intent.tab) }
            }

            DeploymentDetailIntent.ShowScaleDialog -> {
                val currentReplicas = _uiState.value.deployment?.desiredReplicas ?: 0
                _uiState.update {
                    it.copy(
                        isScaleDialogVisible = true,
                        scaleReplicas = currentReplicas,
                    )
                }
            }

            DeploymentDetailIntent.DismissScaleDialog -> {
                _uiState.update { it.copy(isScaleDialogVisible = false) }
            }

            DeploymentDetailIntent.IncreaseScale -> {
                _uiState.update { it.copy(scaleReplicas = it.scaleReplicas + 1) }
            }

            DeploymentDetailIntent.DecreaseScale -> {
                _uiState.update { state ->
                    state.copy(scaleReplicas = (state.scaleReplicas - 1).coerceAtLeast(0))
                }
            }

            DeploymentDetailIntent.ConfirmScale -> confirmScale()
            DeploymentDetailIntent.TogglePauseResume -> togglePauseResume()
            is DeploymentDetailIntent.RollbackToRevision -> rollback(toRevision = intent.revision)
            DeploymentDetailIntent.RetryRolloutHistory -> refreshRolloutHistory()
            DeploymentDetailIntent.ConsumeActionMessage -> {
                _uiState.update { it.copy(actionMessage = null) }
            }
        }
    }

    private fun load(namespace: String, deploymentName: String) {
        val normalizedNamespace = namespace.trim().ifBlank { DEFAULT_NAMESPACE }
        val normalizedDeploymentName = deploymentName.trim()

        _uiState.update {
            it.copy(
                namespace = normalizedNamespace,
                deploymentName = normalizedDeploymentName,
                selectedTab = DeploymentDetailTab.OVERVIEW,
                rolloutHistoryState = RolloutHistoryUiState.Loading,
                actionMessage = null,
            )
        }

        viewModelScope.launch {
            repository.list(normalizedNamespace)
                .onSuccess { data ->
                    val selected = data.items.firstOrNull { deployment ->
                        deployment.name == normalizedDeploymentName
                    }
                    _uiState.update {
                        it.copy(
                            deployment = selected,
                            scaleReplicas = selected?.desiredReplicas ?: 0,
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.e(TAG, "Failed to load deployment details", throwable)
                    _uiState.update {
                        it.copy(
                            actionMessage = DeploymentActionMessage.Failure(
                                action = DeploymentUserAction.SCALE,
                                error = DeploymentActionError.UNKNOWN,
                            ),
                        )
                    }
                }

            refreshRolloutHistory()
        }
    }

    private fun confirmScale() {
        val state = _uiState.value
        val deploymentName = state.deploymentName
        if (deploymentName.isBlank()) {
            return
        }

        _uiState.update { it.copy(isActionInProgress = true) }

        viewModelScope.launch {
            val result = repository.scale(
                namespace = state.namespace,
                deploymentName = deploymentName,
                replicas = state.scaleReplicas,
            )

            _uiState.update { current ->
                when (result) {
                    is ScaleResult.Success -> {
                        current.copy(
                            deployment = current.deployment?.copy(
                                desiredReplicas = result.currentReplicas,
                            ),
                            isActionInProgress = false,
                            isScaleDialogVisible = false,
                            actionMessage = DeploymentActionMessage.Success(
                                action = DeploymentUserAction.SCALE,
                            ),
                        )
                    }

                    ScaleResult.InvalidReplicaCount -> {
                        current.copy(
                            isActionInProgress = false,
                            actionMessage = DeploymentActionMessage.Failure(
                                action = DeploymentUserAction.SCALE,
                                error = DeploymentActionError.INVALID_REPLICA_COUNT,
                            ),
                        )
                    }

                    ScaleResult.NotFound -> {
                        current.copy(
                            isActionInProgress = false,
                            actionMessage = DeploymentActionMessage.Failure(
                                action = DeploymentUserAction.SCALE,
                                error = DeploymentActionError.NOT_FOUND,
                            ),
                        )
                    }

                    ScaleResult.Forbidden -> {
                        current.copy(
                            isActionInProgress = false,
                            actionMessage = DeploymentActionMessage.Failure(
                                action = DeploymentUserAction.SCALE,
                                error = DeploymentActionError.FORBIDDEN,
                            ),
                        )
                    }

                    ScaleResult.Conflict -> {
                        current.copy(
                            isActionInProgress = false,
                            actionMessage = DeploymentActionMessage.Failure(
                                action = DeploymentUserAction.SCALE,
                                error = DeploymentActionError.CONFLICT,
                            ),
                        )
                    }

                    is ScaleResult.Failure -> {
                        Log.e(TAG, "Scale action failed", result.cause)
                        current.copy(
                            isActionInProgress = false,
                            actionMessage = DeploymentActionMessage.Failure(
                                action = DeploymentUserAction.SCALE,
                                error = DeploymentActionError.UNKNOWN,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun togglePauseResume() {
        val state = _uiState.value
        val deploymentName = state.deploymentName
        if (deploymentName.isBlank()) {
            return
        }

        _uiState.update { it.copy(isActionInProgress = true) }

        viewModelScope.launch {
            val currentlyPaused = _uiState.value.isRolloutPaused
            val result = if (currentlyPaused) {
                repository.resume(state.namespace, deploymentName)
            } else {
                repository.pause(state.namespace, deploymentName)
            }

            _uiState.update { current ->
                when (result) {
                    is RolloutResult.Success -> {
                        current.copy(
                            isRolloutPaused = !currentlyPaused,
                            isActionInProgress = false,
                            actionMessage = DeploymentActionMessage.Success(
                                action = if (currentlyPaused) {
                                    DeploymentUserAction.RESUME
                                } else {
                                    DeploymentUserAction.PAUSE
                                },
                            ),
                        )
                    }

                    RolloutResult.NotFound -> current.withRolloutFailure(
                        action = if (currentlyPaused) {
                            DeploymentUserAction.RESUME
                        } else {
                            DeploymentUserAction.PAUSE
                        },
                        error = DeploymentActionError.NOT_FOUND,
                    )

                    RolloutResult.Forbidden -> current.withRolloutFailure(
                        action = if (currentlyPaused) {
                            DeploymentUserAction.RESUME
                        } else {
                            DeploymentUserAction.PAUSE
                        },
                        error = DeploymentActionError.FORBIDDEN,
                    )

                    RolloutResult.Conflict -> current.withRolloutFailure(
                        action = if (currentlyPaused) {
                            DeploymentUserAction.RESUME
                        } else {
                            DeploymentUserAction.PAUSE
                        },
                        error = DeploymentActionError.CONFLICT,
                    )

                    is RolloutResult.Failure -> {
                        Log.e(TAG, "Pause/resume action failed", result.cause)
                        current.withRolloutFailure(
                            action = if (currentlyPaused) {
                                DeploymentUserAction.RESUME
                            } else {
                                DeploymentUserAction.PAUSE
                            },
                            error = DeploymentActionError.UNKNOWN,
                        )
                    }
                }
            }
        }
    }

    private fun rollback(toRevision: Long?) {
        val state = _uiState.value
        val deploymentName = state.deploymentName
        if (deploymentName.isBlank()) {
            return
        }

        _uiState.update { it.copy(isActionInProgress = true) }

        viewModelScope.launch {
            val result = repository.rollback(
                namespace = state.namespace,
                deploymentName = deploymentName,
                toRevision = toRevision,
            )

            _uiState.update { current ->
                when (result) {
                    is RolloutResult.Success -> {
                        current.copy(
                            isActionInProgress = false,
                            actionMessage = DeploymentActionMessage.Success(
                                action = DeploymentUserAction.ROLLBACK,
                            ),
                        )
                    }

                    RolloutResult.NotFound -> current.withRolloutFailure(
                        action = DeploymentUserAction.ROLLBACK,
                        error = DeploymentActionError.NOT_FOUND,
                    )

                    RolloutResult.Forbidden -> current.withRolloutFailure(
                        action = DeploymentUserAction.ROLLBACK,
                        error = DeploymentActionError.FORBIDDEN,
                    )

                    RolloutResult.Conflict -> current.withRolloutFailure(
                        action = DeploymentUserAction.ROLLBACK,
                        error = DeploymentActionError.CONFLICT,
                    )

                    is RolloutResult.Failure -> {
                        Log.e(TAG, "Rollback action failed", result.cause)
                        current.withRolloutFailure(
                            action = DeploymentUserAction.ROLLBACK,
                            error = DeploymentActionError.UNKNOWN,
                        )
                    }
                }
            }

            refreshRolloutHistory()
        }
    }

    private fun refreshRolloutHistory() {
        val state = _uiState.value
        val deploymentName = state.deploymentName
        if (deploymentName.isBlank()) {
            return
        }

        _uiState.update { it.copy(rolloutHistoryState = RolloutHistoryUiState.Loading) }

        viewModelScope.launch {
            repository.history(state.namespace, deploymentName)
                .fold(
                    onSuccess = { history ->
                        _uiState.update {
                            it.copy(
                                rolloutHistoryState = if (history.isEmpty()) {
                                    RolloutHistoryUiState.Empty
                                } else {
                                    RolloutHistoryUiState.Success(revisions = history)
                                },
                            )
                        }
                    },
                    onFailure = { throwable ->
                        Log.e(TAG, "Failed to load rollout history", throwable)
                        _uiState.update {
                            it.copy(rolloutHistoryState = RolloutHistoryUiState.Error(cause = throwable))
                        }
                    },
                )
        }
    }

    private fun DeploymentDetailUiState.withRolloutFailure(
        action: DeploymentUserAction,
        error: DeploymentActionError,
    ): DeploymentDetailUiState = copy(
        isActionInProgress = false,
        actionMessage = DeploymentActionMessage.Failure(
            action = action,
            error = error,
        ),
    )
}
