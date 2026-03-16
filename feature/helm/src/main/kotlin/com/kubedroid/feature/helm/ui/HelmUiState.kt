package com.kubedroid.feature.helm.ui

import com.kubedroid.feature.helm.model.HelmRelease

sealed interface HelmUiState {
    data object Loading : HelmUiState
    data object Empty : HelmUiState
    data class Success(val releases: List<HelmRelease>) : HelmUiState
    data class Error(val cause: Throwable?) : HelmUiState
}
