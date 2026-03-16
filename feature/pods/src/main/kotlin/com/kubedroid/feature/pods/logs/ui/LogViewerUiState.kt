package com.kubedroid.feature.pods.logs.ui

import com.kubedroid.feature.pods.logs.LogLine

data class LogViewerUiState(
    val isLoading: Boolean = false,
    val streamId: String = "",
    val lines: List<LogLine> = emptyList(),
    val visibleLines: List<LogLine> = emptyList(),
    val containers: List<String> = emptyList(),
    val selectedContainer: String? = null,
    val searchQuery: String = "",
    val isFollowEnabled: Boolean = true,
    val isTimestampEnabled: Boolean = true,
    val isEmpty: Boolean = false,
    val errorResId: Int? = null,
    val errorMessage: String? = null,
) {
    fun visibleLogText(): String = visibleLines.joinToString(separator = "\n") { line ->
        if (isTimestampEnabled && line.timestamp != null) {
            "${line.timestamp} ${line.message}"
        } else {
            line.message
        }
    }
}
