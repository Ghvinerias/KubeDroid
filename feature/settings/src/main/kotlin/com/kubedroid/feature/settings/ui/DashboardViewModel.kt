package com.kubedroid.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.feature.settings.MultiClusterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val multiClusterRepository: MultiClusterRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshGeneration: Long = 0

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshGeneration += 1
        val generation = refreshGeneration
        refreshJob = viewModelScope.launch {
            _isRefreshing.value = true
            if (_uiState.value !is DashboardUiState.Success) {
                _uiState.value = DashboardUiState.Loading
            }

            try {
                multiClusterRepository.getAllClusterSummaries()
                    .catch { throwable ->
                        _uiState.value = DashboardUiState.Error(throwable)
                    }
                    .collect { summaries ->
                        _uiState.update {
                            if (summaries.isEmpty()) {
                                DashboardUiState.Empty
                            } else {
                                DashboardUiState.Success(summaries)
                            }
                        }
                    }
            } finally {
                if (generation == refreshGeneration) {
                    _isRefreshing.value = false
                }
            }
        }
    }
}
