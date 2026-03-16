package com.kubedroid.feature.onboarding

import com.kubedroid.core.network.connection.ClusterConnectionState

enum class ImportOption {
    PasteText,
    PickFile,
    ScanQr,
}

data class ClusterSummary(
    val contextName: String = "",
    val server: String = "",
)

data class OnboardingState(
    val isCheckingCompletion: Boolean = true,
    val isOnboardingComplete: Boolean = false,
    val currentStep: OnboardingStep = OnboardingStep.Welcome,
    val kubeconfigInput: String = "",
    val selectedImportOption: ImportOption = ImportOption.PasteText,
    val isSavingKubeconfig: Boolean = false,
    val connectionState: ClusterConnectionState = ClusterConnectionState.Disconnected,
    val clusterSummary: ClusterSummary = ClusterSummary(),
    val errorMessage: String? = null,
)
