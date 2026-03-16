package com.kubedroid.feature.pods.exec.ui

/**
 * UI states for the pod exec screen.
 */
sealed class ExecUiState {
    data object Idle : ExecUiState()
    data object Connecting : ExecUiState()
    data object Active : ExecUiState()
    data class Exited(val exitCode: Int) : ExecUiState()
    data class Error(val message: String?) : ExecUiState()
}
