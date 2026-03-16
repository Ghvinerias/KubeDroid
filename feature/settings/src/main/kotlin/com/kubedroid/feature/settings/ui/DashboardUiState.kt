package com.kubedroid.feature.settings.ui

import com.kubedroid.feature.settings.ClusterSummary

sealed class DashboardUiState {
    data object Loading : DashboardUiState()
    data object Empty : DashboardUiState()
    data class Success(val summaries: List<ClusterSummary>) : DashboardUiState()
    data class Error(val cause: Throwable?) : DashboardUiState()
}
