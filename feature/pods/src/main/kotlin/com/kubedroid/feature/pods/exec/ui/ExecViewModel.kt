package com.kubedroid.feature.pods.exec.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.feature.pods.exec.ExecRepository
import com.kubedroid.feature.pods.exec.PodExecRequest
import com.kubedroid.feature.pods.exec.PodExecSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ExecIntent {
    data class Connect(val request: PodExecRequest) : ExecIntent
    data class SendInput(val text: String) : ExecIntent
    data class Resize(val columns: Int, val rows: Int) : ExecIntent
    data object Disconnect : ExecIntent
}

sealed interface ExecTerminalEvent {
    data class Stdout(val text: String) : ExecTerminalEvent
    data class Stderr(val text: String) : ExecTerminalEvent
    data object Clear : ExecTerminalEvent
}

@HiltViewModel
class ExecViewModel @Inject constructor(
    private val execRepository: ExecRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ExecUiState>(ExecUiState.Idle)
    val uiState: StateFlow<ExecUiState> = _uiState.asStateFlow()

    private val _terminalEvents = MutableSharedFlow<ExecTerminalEvent>(
        replay = 128,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val terminalEvents: SharedFlow<ExecTerminalEvent> = _terminalEvents.asSharedFlow()

    private var activeSession: PodExecSession? = null
    private var outputJob: Job? = null
    private var errorJob: Job? = null
    private var exitJob: Job? = null

    fun onIntent(intent: ExecIntent) {
        when (intent) {
            is ExecIntent.Connect -> connect(intent.request)
            is ExecIntent.SendInput -> sendInput(intent.text)
            is ExecIntent.Resize -> resize(intent.columns, intent.rows)
            ExecIntent.Disconnect -> disconnect(setIdle = true)
        }
    }

    private fun connect(request: PodExecRequest) {
        disconnect(setIdle = false)
        _uiState.value = ExecUiState.Connecting
        _terminalEvents.tryEmit(ExecTerminalEvent.Clear)

        viewModelScope.launch {
            execRepository.createSession(request)
                .onSuccess { session ->
                    activeSession = session
                    _uiState.value = ExecUiState.Active
                    observeSession(session)
                }
                .onFailure { throwable ->
                    _uiState.value = ExecUiState.Error(throwable.message)
                }
        }
    }

    private fun observeSession(session: PodExecSession) {
        outputJob?.cancel()
        errorJob?.cancel()
        exitJob?.cancel()

        outputJob = viewModelScope.launch {
            session.outputFlow.collect { output ->
                _terminalEvents.emit(ExecTerminalEvent.Stdout(output))
            }
        }

        errorJob = viewModelScope.launch {
            session.errorFlow.collect { error ->
                _terminalEvents.emit(ExecTerminalEvent.Stderr(error))
            }
        }

        exitJob = viewModelScope.launch {
            session.exitCodeFlow.collect { code ->
                _uiState.value = ExecUiState.Exited(code)
                disconnect(setIdle = false)
            }
        }
    }

    private fun sendInput(text: String) {
        val session = activeSession ?: return
        viewModelScope.launch {
            runCatching { session.sendInput(text) }
                .onFailure { throwable ->
                    _uiState.value = ExecUiState.Error(throwable.message)
                }
        }
    }

    private fun resize(columns: Int, rows: Int) {
        val session = activeSession ?: return
        viewModelScope.launch {
            runCatching { session.resize(columns = columns, rows = rows) }
        }
    }

    private fun disconnect(setIdle: Boolean) {
        outputJob?.cancel()
        errorJob?.cancel()
        exitJob?.cancel()
        outputJob = null
        errorJob = null
        exitJob = null

        activeSession?.close()
        activeSession = null

        if (setIdle) {
            _uiState.value = ExecUiState.Idle
        }
    }

    override fun onCleared() {
        disconnect(setIdle = false)
        super.onCleared()
    }
}
