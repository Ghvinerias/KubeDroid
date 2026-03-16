package com.kubedroid.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.feature.settings.domain.model.AppPreferences
import com.kubedroid.feature.settings.domain.model.AppTheme
import com.kubedroid.feature.settings.domain.model.LogBufferSize
import com.kubedroid.feature.settings.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: AppPreferences = AppPreferences(),
    val cacheSizeBytes: Long = 0L,
    val appVersion: String = "",
    val appBuildNumber: String = "",
    val isClearingCache: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.getPreferences().collect { preferences ->
                _uiState.update { state -> state.copy(preferences = preferences) }
            }
        }
        _uiState.update { state ->
            state.copy(
                appVersion = preferencesRepository.getAppVersion(),
                appBuildNumber = preferencesRepository.getAppBuildNumber(),
            )
        }
        refreshCacheSize()
    }

    fun onThemeSelected(theme: AppTheme) {
        updatePreferences { preferences -> preferences.copy(theme = theme) }
    }

    fun onDefaultNamespaceChanged(value: String) {
        updatePreferences { preferences -> preferences.copy(defaultNamespace = value) }
    }

    fun onLogBufferSizeSelected(value: LogBufferSize) {
        updatePreferences { preferences -> preferences.copy(logBufferSize = value) }
    }

    fun onMetricsRefreshSecondsSelected(value: Int) {
        updatePreferences { preferences -> preferences.copy(metricsRefreshSeconds = value) }
    }

    fun onBiometricLockChanged(enabled: Boolean) {
        updatePreferences { preferences -> preferences.copy(biometricLockEnabled = enabled) }
    }

    fun clearCache() {
        viewModelScope.launch {
            _uiState.update { state -> state.copy(isClearingCache = true) }
            preferencesRepository.clearCache()
            refreshCacheSize()
            _uiState.update { state -> state.copy(isClearingCache = false) }
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val cacheSizeBytes = preferencesRepository.getCacheSizeBytes()
            _uiState.update { state -> state.copy(cacheSizeBytes = cacheSizeBytes) }
        }
    }

    private fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        viewModelScope.launch {
            val updated = transform(_uiState.value.preferences)
            preferencesRepository.updatePreferences(updated)
        }
    }
}
