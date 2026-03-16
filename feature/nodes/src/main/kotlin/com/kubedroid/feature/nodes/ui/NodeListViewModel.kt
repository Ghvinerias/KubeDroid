package com.kubedroid.feature.nodes.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.database.cache.StaleDataIndicator
import com.kubedroid.core.network.nodes.Node
import com.kubedroid.feature.nodes.NodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface NodeListIntent {
    data object Load : NodeListIntent
    data object Retry : NodeListIntent
}

@HiltViewModel
class NodeListViewModel @Inject constructor(
    private val repository: NodeRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "NodeListViewModel"
    }

    private val _uiState = MutableStateFlow(NodeListUiState())
    val uiState: StateFlow<NodeListUiState> = _uiState.asStateFlow()

    private var watchJob: Job? = null

    fun onIntent(intent: NodeListIntent) {
        when (intent) {
            NodeListIntent.Load,
            NodeListIntent.Retry,
            -> load()
        }
    }

    private fun load() {
        watchJob?.cancel()
        _uiState.update {
            it.copy(
                listState = NodeListContentState.Loading,
                isDataFromCache = false,
                lastUpdatedAtEpochMillis = null,
            )
        }

        viewModelScope.launch {
            repository.list()
                .fold(
                    onSuccess = { data ->
                        _uiState.update {
                            it.copy(
                                listState = data.items.toListContentState(),
                                isDataFromCache = data.staleDataIndicator.source == StaleDataIndicator.Source.Cache,
                                lastUpdatedAtEpochMillis = data.staleDataIndicator.lastFetchedAt,
                            )
                        }
                        observeWatchUpdates()
                    },
                    onFailure = { throwable ->
                        Log.e(TAG, "Failed to load nodes", throwable)
                        _uiState.update {
                            it.copy(listState = NodeListContentState.Error(cause = throwable))
                        }
                    },
                )
        }
    }

    private fun observeWatchUpdates() {
        watchJob = viewModelScope.launch {
            repository.watch().collect { result ->
                result.fold(
                    onSuccess = { data ->
                        _uiState.update {
                            it.copy(
                                listState = data.items.toListContentState(),
                                isDataFromCache = data.staleDataIndicator.source == StaleDataIndicator.Source.Cache,
                                lastUpdatedAtEpochMillis = data.staleDataIndicator.lastFetchedAt,
                            )
                        }
                    },
                    onFailure = { throwable ->
                        Log.e(TAG, "Failed while watching node updates", throwable)
                        _uiState.update {
                            it.copy(listState = NodeListContentState.Error(cause = throwable))
                        }
                    },
                )
            }
        }
    }
}

private fun List<Node>.toListContentState(): NodeListContentState {
    if (isEmpty()) {
        return NodeListContentState.Empty
    }

    return NodeListContentState.Success(
        items = map { node ->
            NodeListItem(
                stableKey = node.name,
                name = node.name,
                roles = node.roles,
                ready = node.ready,
                unschedulable = node.unschedulable,
                cpuPercent = node.metrics?.cpuUsagePercent,
                memoryPercent = node.metrics?.memoryUsagePercent,
            )
        },
    )
}
