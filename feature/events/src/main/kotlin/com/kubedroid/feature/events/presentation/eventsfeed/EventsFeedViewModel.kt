package com.kubedroid.feature.events.presentation.eventsfeed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.namespace.ALL_NAMESPACES
import com.kubedroid.core.network.namespace.NamespaceStore
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import com.kubedroid.feature.events.domain.model.ClusterEvent
import com.kubedroid.feature.events.domain.model.ClusterEventType
import com.kubedroid.feature.events.domain.repository.EventsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class EventsFeedViewModel @Inject constructor(
    private val repository: EventsRepository,
    private val namespaceStore: NamespaceStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EventsFeedUiState())
    val uiState: StateFlow<EventsFeedUiState> = _uiState.asStateFlow()

    private var watchJob: Job? = null
    private var latestEvents: List<ClusterEvent> = emptyList()

    init {
        observeSelectedNamespace()
        observeEvents()
    }

    fun onIntent(intent: EventsFeedIntent) {
        when (intent) {
            EventsFeedIntent.Retry -> observeEvents(force = true)
            is EventsFeedIntent.SelectNamespace -> {
                val selectedNamespace = intent.namespace?.trim()?.takeUnless { it.isEmpty() } ?: ALL_NAMESPACES
                viewModelScope.launch {
                    namespaceStore.setNamespace(selectedNamespace)
                }
            }

            is EventsFeedIntent.SelectType -> {
                _uiState.update { it.copy(typeFilter = intent.filter) }
                updateUiStateFromEvents()
            }

            is EventsFeedIntent.UpdateSearchQuery -> {
                _uiState.update { it.copy(searchQuery = intent.query) }
                updateUiStateFromEvents()
            }
        }
    }

    private fun observeSelectedNamespace() {
        viewModelScope.launch {
            namespaceStore.selectedNamespace
                .distinctUntilChanged()
                .collect { namespace ->
                    _uiState.update {
                        it.copy(
                            selectedNamespace = if (namespace.isAllNamespacesSelection()) null else namespace,
                        )
                    }
                    updateUiStateFromEvents()
                }
        }
    }

    private fun observeEvents(force: Boolean = false) {
        if (watchJob?.isActive == true && !force) return
        watchJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }
        latestEvents = emptyList()

        watchJob = viewModelScope.launch {
            try {
                repository.watchAllEvents(namespace = null).collect { events ->
                    latestEvents = events
                    updateUiStateFromEvents(events)
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                Log.e(TAG, "Failed to watch events", throwable)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = throwable.localizedMessage ?: throwable.message,
                    )
                }
            }
        }
    }

    private fun updateUiStateFromEvents(events: List<ClusterEvent> = latestEvents) {
        val state = _uiState.value
        val search = state.searchQuery.trim()
        val allEvents = events
            .sortedByDescending { it.lastTimestamp }
        val namespaces = allEvents
            .asSequence()
            .map { it.namespace }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

        val filteredEvents = allEvents.filter { event ->
            val namespaceMatch = state.selectedNamespace == null || event.namespace == state.selectedNamespace
            val typeMatch = when (state.typeFilter) {
                EventTypeFilter.All -> true
                EventTypeFilter.Warning -> event.type == ClusterEventType.Warning
                EventTypeFilter.Normal -> event.type == ClusterEventType.Normal
            }
            val searchMatch = if (search.isBlank()) {
                true
            } else {
                val haystack = buildString {
                    append(event.involvedObjectName)
                    append(' ')
                    append(event.reason)
                    append(' ')
                    append(event.message)
                    append(' ')
                    append(event.namespace)
                }
                haystack.contains(search, ignoreCase = true)
            }
            namespaceMatch && typeMatch && searchMatch
        }

        _uiState.update {
            it.copy(
                availableNamespaces = namespaces,
                events = filteredEvents,
                warningCount = allEvents.count { event -> event.type == ClusterEventType.Warning },
                isLoading = false,
                error = null,
            )
        }
    }

    companion object {
        private const val TAG = "EventsFeedViewModel"
    }
}
