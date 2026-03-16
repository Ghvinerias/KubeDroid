package com.kubedroid.feature.pods.resources

/**
 * Generic UI state for rendering resource list screens.
 */
sealed interface ResourceListUiState<out T> {
    data object Loading : ResourceListUiState<Nothing>
    data object Empty : ResourceListUiState<Nothing>
    data class Success<T>(val items: List<T>) : ResourceListUiState<T>
    data class Error(val cause: Throwable?) : ResourceListUiState<Nothing>
}
