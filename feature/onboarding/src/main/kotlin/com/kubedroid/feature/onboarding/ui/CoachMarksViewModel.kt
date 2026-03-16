package com.kubedroid.feature.onboarding.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.feature.onboarding.OnboardingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CoachMarksUiState(
    val isLoading: Boolean = true,
    val shouldShow: Boolean = false,
)

@HiltViewModel
class CoachMarksViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CoachMarksUiState())
    val state: StateFlow<CoachMarksUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val shouldShowCoachMarks = onboardingRepository.isOnboardingComplete() &&
                !onboardingRepository.areCoachMarksComplete()
            _state.update {
                it.copy(
                    isLoading = false,
                    shouldShow = shouldShowCoachMarks,
                )
            }
        }
    }

    fun completeCoachMarks() {
        viewModelScope.launch {
            onboardingRepository.markCoachMarksComplete()
            _state.update {
                it.copy(
                    shouldShow = false,
                )
            }
        }
    }
}
