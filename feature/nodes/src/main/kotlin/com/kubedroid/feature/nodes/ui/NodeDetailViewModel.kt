package com.kubedroid.feature.nodes.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.nodes.Node
import com.kubedroid.core.network.nodes.NodeActionResult
import com.kubedroid.core.network.nodes.NodeDrainProgress
import com.kubedroid.feature.nodes.NodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface NodeDetailIntent {
    data class Load(val nodeName: String) : NodeDetailIntent
    data class SelectTab(val tab: NodeDetailTab) : NodeDetailIntent
    data class RequestActionConfirmation(val action: NodeActionType) : NodeDetailIntent
    data object DismissActionConfirmation : NodeDetailIntent
    data object ConfirmAction : NodeDetailIntent
    data object ConsumeActionMessage : NodeDetailIntent
    data object DismissDrainProgressDialog : NodeDetailIntent
}

@HiltViewModel
class NodeDetailViewModel @Inject constructor(
    private val repository: NodeRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "NodeDetailViewModel"
    }

    private val _uiState = MutableStateFlow(NodeDetailUiState())
    val uiState: StateFlow<NodeDetailUiState> = _uiState.asStateFlow()

    private var watchJob: Job? = null

    fun onIntent(intent: NodeDetailIntent) {
        when (intent) {
            is NodeDetailIntent.Load -> load(nodeName = intent.nodeName)
            is NodeDetailIntent.SelectTab -> _uiState.update { it.copy(selectedTab = intent.tab) }
            is NodeDetailIntent.RequestActionConfirmation -> {
                _uiState.update { it.copy(activeConfirmation = intent.action) }
            }

            NodeDetailIntent.DismissActionConfirmation -> {
                _uiState.update { it.copy(activeConfirmation = null) }
            }

            NodeDetailIntent.ConfirmAction -> runConfirmedAction()
            NodeDetailIntent.ConsumeActionMessage -> _uiState.update { it.copy(actionMessage = null) }
            NodeDetailIntent.DismissDrainProgressDialog -> {
                _uiState.update { state ->
                    state.copy(
                        drainState = if (state.drainState.isCompleted) {
                            DrainProgressUiState()
                        } else {
                            state.drainState.copy(isVisible = false)
                        },
                    )
                }
            }
        }
    }

    private fun load(nodeName: String) {
        val normalizedName = nodeName.trim()
        if (normalizedName.isBlank()) {
            _uiState.update {
                it.copy(
                    nodeName = "",
                    isLoading = false,
                    error = IllegalArgumentException("Node name is required"),
                )
            }
            return
        }

        watchJob?.cancel()
        _uiState.update {
            it.copy(
                nodeName = normalizedName,
                isLoading = true,
                error = null,
                actionMessage = null,
            )
        }

        viewModelScope.launch {
            repository.list()
                .fold(
                    onSuccess = { data ->
                        _uiState.update { current ->
                            current.copy(
                                node = data.items.firstOrNull { it.name == normalizedName },
                                isLoading = false,
                                error = null,
                            )
                        }
                        observeNodeUpdates(nodeName = normalizedName)
                    },
                    onFailure = { throwable ->
                        Log.e(TAG, "Failed to load node details", throwable)
                        _uiState.update {
                            it.copy(
                                node = null,
                                isLoading = false,
                                error = throwable,
                            )
                        }
                    },
                )
        }
    }

    private fun observeNodeUpdates(nodeName: String) {
        watchJob = viewModelScope.launch {
            repository.watch().collect { result ->
                result.fold(
                    onSuccess = { data ->
                        _uiState.update { current ->
                            current.copy(
                                node = data.items.firstOrNull { it.name == nodeName },
                                error = null,
                            )
                        }
                    },
                    onFailure = { throwable ->
                        Log.e(TAG, "Failed while observing node updates", throwable)
                        _uiState.update { it.copy(error = throwable) }
                    },
                )
            }
        }
    }

    private fun runConfirmedAction() {
        val state = _uiState.value
        val action = state.activeConfirmation ?: return
        val nodeName = state.nodeName
        _uiState.update { it.copy(activeConfirmation = null) }

        viewModelScope.launch {
            when (action) {
                NodeActionType.CORDON -> {
                    _uiState.update { it.copy(isActionInProgress = true) }
                    applyActionResult(action, repository.cordon(nodeName))
                }

                NodeActionType.UNCORDON -> {
                    _uiState.update { it.copy(isActionInProgress = true) }
                    applyActionResult(action, repository.uncordon(nodeName))
                }

                NodeActionType.DRAIN -> {
                    _uiState.update {
                        it.copy(
                            isActionInProgress = true,
                            drainState = DrainProgressUiState(
                                isVisible = true,
                                nodeName = nodeName,
                            ),
                        )
                    }
                    repository.drainWithProgress(nodeName).collect { progress ->
                        applyDrainProgress(progress)
                    }
                }
            }
        }
    }

    private fun applyActionResult(
        action: NodeActionType,
        result: NodeActionResult,
    ) {
        _uiState.update { current ->
            current.copy(
                isActionInProgress = false,
                actionMessage = result.toActionMessage(action),
            )
        }
    }

    private fun applyDrainProgress(progress: NodeDrainProgress) {
        when (progress) {
            is NodeDrainProgress.Started -> {
                _uiState.update { state ->
                    state.copy(
                        drainState = state.drainState.copy(
                            isVisible = true,
                            nodeName = progress.nodeName,
                            totalPods = progress.totalPods,
                            evictedPods = 0,
                            events = state.drainState.events + progress.toUiEvent(),
                        ),
                    )
                }
            }

            is NodeDrainProgress.EvictionAttempt -> {
                _uiState.update { state ->
                    state.copy(
                        drainState = state.drainState.copy(
                            events = state.drainState.events + progress.toUiEvent(),
                        ),
                    )
                }
            }

            is NodeDrainProgress.WaitingOnDisruptionBudget -> {
                _uiState.update { state ->
                    state.copy(
                        drainState = state.drainState.copy(
                            events = state.drainState.events + progress.toUiEvent(),
                        ),
                    )
                }
            }

            is NodeDrainProgress.PodEvicted -> {
                _uiState.update { state ->
                    state.copy(
                        drainState = state.drainState.copy(
                            totalPods = progress.totalPods,
                            evictedPods = progress.evictedPods,
                            events = state.drainState.events + progress.toUiEvent(),
                        ),
                    )
                }
            }

            is NodeDrainProgress.Completed -> {
                val terminalError = (progress.result as? NodeActionResult.Failure)?.cause
                if (terminalError != null) {
                    Log.e(TAG, "Node drain failed", terminalError)
                }
                _uiState.update { state ->
                    state.copy(
                        isActionInProgress = false,
                        actionMessage = progress.result.toActionMessage(NodeActionType.DRAIN),
                        drainState = state.drainState.copy(
                            isVisible = true,
                            isCompleted = true,
                            events = state.drainState.events + progress.toUiEvent(),
                        ),
                    )
                }
            }
        }
    }
}

internal fun NodeActionResult.toActionMessage(action: NodeActionType): NodeActionMessage {
    return when (this) {
        is NodeActionResult.Success -> NodeActionMessage.Success(action = action)
        NodeActionResult.NotFound -> NodeActionMessage.Failure(
            action = action,
            error = NodeActionError.NOT_FOUND,
        )

        NodeActionResult.Forbidden -> NodeActionMessage.Failure(
            action = action,
            error = NodeActionError.FORBIDDEN,
        )

        NodeActionResult.Conflict -> NodeActionMessage.Failure(
            action = action,
            error = NodeActionError.CONFLICT,
        )

        is NodeActionResult.Failure -> NodeActionMessage.Failure(
            action = action,
            error = NodeActionError.UNKNOWN,
        )
    }
}

internal fun NodeDrainProgress.toUiEvent(): DrainProgressEventUi {
    return when (this) {
        is NodeDrainProgress.Started -> DrainProgressEventUi(
            messageType = DrainEventMessageType.STARTED,
            namespace = null,
            podName = null,
            attempt = null,
            retryDelayMillis = null,
        )

        is NodeDrainProgress.EvictionAttempt -> DrainProgressEventUi(
            messageType = DrainEventMessageType.EVICTION_ATTEMPT,
            namespace = namespace,
            podName = podName,
            attempt = attempt,
            retryDelayMillis = null,
        )

        is NodeDrainProgress.WaitingOnDisruptionBudget -> DrainProgressEventUi(
            messageType = DrainEventMessageType.WAITING_ON_PDB,
            namespace = namespace,
            podName = podName,
            attempt = attempt,
            retryDelayMillis = retryDelayMillis,
        )

        is NodeDrainProgress.PodEvicted -> DrainProgressEventUi(
            messageType = DrainEventMessageType.POD_EVICTED,
            namespace = namespace,
            podName = podName,
            attempt = null,
            retryDelayMillis = null,
        )

        is NodeDrainProgress.Completed -> {
            val messageType = when (result) {
                is NodeActionResult.Success -> DrainEventMessageType.COMPLETED_SUCCESS
                NodeActionResult.NotFound -> DrainEventMessageType.COMPLETED_NOT_FOUND
                NodeActionResult.Forbidden -> DrainEventMessageType.COMPLETED_FORBIDDEN
                NodeActionResult.Conflict -> DrainEventMessageType.COMPLETED_CONFLICT
                is NodeActionResult.Failure -> DrainEventMessageType.COMPLETED_FAILURE
            }
            DrainProgressEventUi(
                messageType = messageType,
                namespace = null,
                podName = null,
                attempt = null,
                retryDelayMillis = null,
            )
        }
    }
}
