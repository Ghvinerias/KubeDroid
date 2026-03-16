package com.kubedroid.feature.helm.presentation.state

import com.kubedroid.feature.helm.domain.model.HelmRelease

sealed class HelmUiState {
    data object Loading : HelmUiState()
    data class Success(val releases: List<HelmRelease>) : HelmUiState()
    data class Error(val message: String) : HelmUiState()
}
