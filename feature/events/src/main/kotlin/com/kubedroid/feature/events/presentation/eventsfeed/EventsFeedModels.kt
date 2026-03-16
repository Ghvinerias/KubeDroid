package com.kubedroid.feature.events.presentation.eventsfeed

enum class EventTypeFilter {
    All,
    Warning,
    Normal,
}

sealed interface EventsFeedIntent {
    data object Retry : EventsFeedIntent
    data class SelectNamespace(val namespace: String?) : EventsFeedIntent
    data class SelectType(val filter: EventTypeFilter) : EventsFeedIntent
    data class UpdateSearchQuery(val query: String) : EventsFeedIntent
}
