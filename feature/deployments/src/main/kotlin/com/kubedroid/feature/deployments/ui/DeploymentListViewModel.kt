package com.kubedroid.feature.deployments.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.database.cache.StaleDataIndicator
import com.kubedroid.core.network.namespace.NamespaceStore
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import com.kubedroid.core.network.pods.NamespaceRepository
import com.kubedroid.feature.deployments.DeploymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DeploymentListIntent {
    data object LoadNamespaces : DeploymentListIntent
    data object Retry : DeploymentListIntent
}

@HiltViewModel
class DeploymentListViewModel @Inject constructor(
    private val repository: DeploymentRepository,
    private val namespaceRepository: NamespaceRepository,
    private val namespaceStore: NamespaceStore,
) : ViewModel() {

    private companion object {
        const val TAG = "DeploymentListVM"
        const val DEFAULT_NAMESPACE = "default"
    }

    private val _uiState = MutableStateFlow(DeploymentListUiState())
    val uiState: StateFlow<DeploymentListUiState> = _uiState.asStateFlow()
    private val _selectedNamespace = MutableStateFlow(DEFAULT_NAMESPACE)
    val selectedNamespace: StateFlow<String> = _selectedNamespace.asStateFlow()

    private var listRequestJob: Job? = null

    init {
        observeSelectedNamespace()
    }

    fun onIntent(intent: DeploymentListIntent) {
        when (intent) {
            DeploymentListIntent.LoadNamespaces -> loadNamespaces()
            DeploymentListIntent.Retry -> {
                val namespace = _selectedNamespace.value
                if (namespace.isBlank()) {
                    loadNamespaces()
                } else {
                    load(namespace = namespace)
                }
            }
        }
    }

    fun selectNamespace(namespace: String) {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isBlank() || normalizedNamespace == _selectedNamespace.value) {
            return
        }
        viewModelScope.launch {
            namespaceStore.setNamespace(normalizedNamespace)
        }
    }

    private fun loadNamespaces() {
        viewModelScope.launch {
            namespaceRepository.listNamespaces().fold(
                onSuccess = { namespaces ->
                    val availableNamespaces = namespaces.filter { it.isNotBlank() }
                    _uiState.update { it.copy(availableNamespaces = availableNamespaces) }
                    if (availableNamespaces.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                namespace = "",
                                listState = DeploymentListContentState.Empty,
                                isDataFromCache = false,
                                lastUpdatedAtEpochMillis = null,
                            )
                        }
                        return@fold
                    }

                    val selected = _selectedNamespace.value
                    if (selected !in availableNamespaces && !selected.isAllNamespacesSelection()) {
                        val fallbackNamespace = if (DEFAULT_NAMESPACE in availableNamespaces) {
                            DEFAULT_NAMESPACE
                        } else {
                            availableNamespaces.first()
                        }
                        viewModelScope.launch {
                            namespaceStore.setNamespace(fallbackNamespace)
                        }
                        return@fold
                    }

                    load(namespace = selected)
                },
                onFailure = { throwable ->
                    val text = throwable.localizedMessage ?: throwable.message ?: "Unknown error"
                    Log.e(TAG, "Failed to load namespaces: $text", throwable)
                    _uiState.update {
                        it.copy(
                            listState = DeploymentListContentState.Error(cause = throwable),
                            isDataFromCache = false,
                            lastUpdatedAtEpochMillis = null,
                        )
                    }
                },
            )
        }
    }

    private fun load(namespace: String) {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isBlank()) {
            return
        }

        listRequestJob?.cancel()
        _uiState.update {
            it.copy(
                namespace = normalizedNamespace,
                listState = DeploymentListContentState.Loading,
                isDataFromCache = false,
                lastUpdatedAtEpochMillis = null,
            )
        }

        listRequestJob = viewModelScope.launch {
            repository.list(namespace = normalizedNamespace)
                .fold(
                    onSuccess = { data ->
                        _uiState.update {
                            it.copy(
                                listState = if (data.items.isEmpty()) {
                                    DeploymentListContentState.Empty
                                } else {
                                    DeploymentListContentState.Success(
                                        items = data.items.map { deployment -> deployment.toListItem() },
                                    )
                                },
                                isDataFromCache = data.staleDataIndicator.source == StaleDataIndicator.Source.Cache,
                                lastUpdatedAtEpochMillis = data.staleDataIndicator.lastFetchedAt,
                            )
                        }
                    },
                    onFailure = { throwable ->
                        val text = throwable.localizedMessage ?: throwable.message ?: "Unknown error"
                        Log.e(TAG, "Failed to load deployments: $text", throwable)
                        _uiState.update {
                            it.copy(
                                listState = DeploymentListContentState.Error(cause = throwable),
                                isDataFromCache = false,
                                lastUpdatedAtEpochMillis = null,
                            )
                        }
                    },
                )
        }
    }

    private fun observeSelectedNamespace() {
        viewModelScope.launch {
            namespaceStore.selectedNamespace
                .distinctUntilChanged()
                .collect { namespace ->
                    _selectedNamespace.value = namespace
                    load(namespace = namespace)
                }
        }
    }
}
