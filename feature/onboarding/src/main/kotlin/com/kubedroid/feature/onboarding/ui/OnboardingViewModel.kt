package com.kubedroid.feature.onboarding.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.connection.ClusterConnectionRepository
import com.kubedroid.core.network.connection.ClusterConnectionState
import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import com.kubedroid.feature.onboarding.ClusterSummary
import com.kubedroid.feature.onboarding.ImportOption
import com.kubedroid.feature.onboarding.OnboardingRepository
import com.kubedroid.feature.onboarding.OnboardingState
import com.kubedroid.feature.onboarding.OnboardingStep
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
    private val clusterConnectionRepository: ClusterConnectionRepository,
    private val kubeConfigRepository: KubeConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val isComplete = onboardingRepository.isOnboardingComplete()
            _state.update {
                it.copy(
                    isCheckingCompletion = false,
                    isOnboardingComplete = isComplete,
                    currentStep = if (isComplete) OnboardingStep.Done else OnboardingStep.Welcome,
                )
            }
        }
    }

    fun onStepChanged(step: OnboardingStep) {
        _state.update {
            it.copy(
                currentStep = step,
                errorMessage = null,
            )
        }
    }

    fun continueFromWelcome() {
        _state.update {
            it.copy(
                currentStep = OnboardingStep.ImportKubeconfig,
                errorMessage = null,
            )
        }
    }

    fun onKubeconfigInputChange(value: String) {
        _state.update { it.copy(kubeconfigInput = value, errorMessage = null) }
    }

    fun onImportOptionSelected(option: ImportOption) {
        _state.update {
            it.copy(
                selectedImportOption = option,
                errorMessage = null,
            )
        }
    }

    fun onPickFileClick() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun onScanQrClick() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun saveKubeconfig() {
        viewModelScope.launch {
            _state.update { it.copy(isSavingKubeconfig = true, errorMessage = null) }
            val result = onboardingRepository.saveOnboardingKubeconfig(_state.value.kubeconfigInput)
            _state.update {
                if (result.isSuccess) {
                    it.copy(
                        isSavingKubeconfig = false,
                        currentStep = OnboardingStep.TestConnection,
                        errorMessage = null,
                    )
                } else {
                    it.copy(
                        isSavingKubeconfig = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to save kubeconfig",
                    )
                }
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    connectionState = ClusterConnectionState.Connecting(it.clusterSummary.contextName.ifBlank { "cluster" }),
                    errorMessage = null,
                )
            }

            val configResult = runCatching { kubeConfigRepository.load().getOrThrow() }
            if (configResult.isFailure) {
                _state.update {
                    it.copy(
                        connectionState = ClusterConnectionState.Failed(
                            contextName = it.clusterSummary.contextName.ifBlank { "cluster" },
                            reason = configResult.exceptionOrNull()?.message,
                        ),
                        errorMessage = configResult.exceptionOrNull()?.message ?: "Connection test failed",
                    )
                }
                return@launch
            }

            val config = configResult.getOrThrow()
            val activeContextName = config.currentContext
            val activeContext = config.contexts.firstOrNull { it.name == activeContextName }

            if (activeContext == null) {
                _state.update {
                    it.copy(
                        connectionState = ClusterConnectionState.Failed(
                            contextName = activeContextName.orEmpty().ifBlank { "cluster" },
                            reason = "No active context in kubeconfig",
                        ),
                        errorMessage = "No active context in kubeconfig",
                    )
                }
                return@launch
            }

            val summary = ClusterSummary(
                contextName = activeContext.name,
                server = config.clusters.firstOrNull { it.name == activeContext.cluster }?.server.orEmpty(),
            )
            _state.update {
                it.copy(
                    clusterSummary = summary,
                    connectionState = ClusterConnectionState.Connecting(summary.contextName),
                )
            }

            val connectResult = clusterConnectionRepository.connect(activeContext)

            if (connectResult.isFailure) {
                _state.update {
                    it.copy(
                        connectionState = ClusterConnectionState.Failed(
                            contextName = summary.contextName,
                            reason = connectResult.exceptionOrNull()?.message,
                        ),
                        errorMessage = connectResult.exceptionOrNull()?.message ?: "Connection test failed",
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    connectionState = ClusterConnectionState.Connected(summary.contextName),
                    currentStep = OnboardingStep.Done,
                    errorMessage = null,
                )
            }
        }
    }

    fun finishOnboarding() {
        viewModelScope.launch {
            onboardingRepository.markOnboardingComplete()
            _state.update {
                it.copy(
                    isOnboardingComplete = true,
                    currentStep = OnboardingStep.Done,
                    errorMessage = null,
                )
            }
        }
    }
}
