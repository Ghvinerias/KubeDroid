package com.kubedroid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.feature.settings.domain.model.AppTheme
import com.kubedroid.feature.settings.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val theme: StateFlow<AppTheme> = preferencesRepository.getPreferences()
        .map { preferences -> preferences.theme }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = AppTheme.System,
        )

    val biometricLockEnabled: StateFlow<Boolean> = preferencesRepository.getPreferences()
        .map { preferences -> preferences.biometricLockEnabled }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = false,
        )
}
