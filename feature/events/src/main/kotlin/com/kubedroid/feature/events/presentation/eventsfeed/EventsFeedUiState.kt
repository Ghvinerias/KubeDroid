package com.kubedroid.feature.events.presentation.eventsfeed

import com.kubedroid.feature.events.domain.model.ClusterEvent

data class EventsFeedUiState(
    val selectedNamespace: String? = null,
    val availableNamespaces: List<String> = emptyList(),
    val typeFilter: EventTypeFilter = EventTypeFilter.All,
    val searchQuery: String = "",
    val warningCount: Int = 0,
    val isLoading: Boolean = true,
    val events: List<ClusterEvent> = emptyList(),
    val error: String? = null,
)
