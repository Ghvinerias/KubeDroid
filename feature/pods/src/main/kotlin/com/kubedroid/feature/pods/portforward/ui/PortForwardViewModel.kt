package com.kubedroid.feature.pods.portforward.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.portforward.PortForwardSession
import com.kubedroid.core.network.portforward.PortForwardStatus
import com.kubedroid.feature.pods.portforward.PodPortForwardRequest
import com.kubedroid.feature.pods.portforward.PortForwardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PortForwardIntent {
    data class Initialize(
        val apiServer: String,
        val namespace: String,
        val bearerToken: String? = null,
        val pods: List<String>,
    ) : PortForwardIntent

    data object OpenStartSheet : PortForwardIntent
    data object DismissStartSheet : PortForwardIntent
    data class SelectPod(val podName: String) : PortForwardIntent
    data class UpdateRemotePort(val value: String) : PortForwardIntent
    data class UpdateLocalPort(val value: String) : PortForwardIntent
    data object StartPortForward : PortForwardIntent
    data class CloseSession(val sessionId: String) : PortForwardIntent
    data class CopyUrl(val url: String) : PortForwardIntent
    data class OpenInBrowser(val url: String) : PortForwardIntent
}

data class PortForwardUiState(
    val sessions: List<PortForwardSessionUiModel> = emptyList(),
    val isStartSheetVisible: Boolean = false,
    val startSheet: StartPortForwardSheetUiState = StartPortForwardSheetUiState(),
)

sealed interface PortForwardUiEvent {
    data class CopyUrl(val url: String) : PortForwardUiEvent
    data class OpenInBrowser(val url: String) : PortForwardUiEvent
}

data class PortForwardRouteRequest(
    val apiServer: String,
    val namespace: String,
    val bearerToken: String? = null,
    val pods: List<String> = emptyList(),
)

@HiltViewModel
class PortForwardViewModel @Inject constructor(
    private val portForwardRepository: PortForwardRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PortForwardUiState())
    val uiState: StateFlow<PortForwardUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PortForwardUiEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<PortForwardUiEvent> = _events.asSharedFlow()

    private var baseRequest: BasePortForwardRequest? = null
    private val activeSessions = linkedMapOf<String, ManagedPortForwardSession>()

    fun onIntent(intent: PortForwardIntent) {
        when (intent) {
            is PortForwardIntent.Initialize -> initialize(intent)
            PortForwardIntent.OpenStartSheet -> _uiState.update { it.copy(isStartSheetVisible = true) }
            PortForwardIntent.DismissStartSheet -> _uiState.update { it.copy(isStartSheetVisible = false) }
            is PortForwardIntent.SelectPod -> {
                _uiState.update { state ->
                    state.copy(startSheet = state.startSheet.copy(selectedPod = intent.podName))
                }
            }

            is PortForwardIntent.UpdateRemotePort -> {
                _uiState.update { state ->
                    state.copy(startSheet = state.startSheet.copy(remotePortInput = intent.value.filter(Char::isDigit)))
                }
            }

            is PortForwardIntent.UpdateLocalPort -> {
                _uiState.update { state ->
                    state.copy(startSheet = state.startSheet.copy(localPortInput = intent.value.filter(Char::isDigit)))
                }
            }

            PortForwardIntent.StartPortForward -> startPortForward()
            is PortForwardIntent.CloseSession -> closeSession(intent.sessionId)
            is PortForwardIntent.CopyUrl -> _events.tryEmit(PortForwardUiEvent.CopyUrl(intent.url))
            is PortForwardIntent.OpenInBrowser -> _events.tryEmit(PortForwardUiEvent.OpenInBrowser(intent.url))
        }
    }

    private fun initialize(intent: PortForwardIntent.Initialize) {
        baseRequest = BasePortForwardRequest(
            apiServer = intent.apiServer,
            namespace = intent.namespace,
            bearerToken = intent.bearerToken,
        )

        _uiState.update { state ->
            state.copy(
                startSheet = state.startSheet.copy(
                    pods = intent.pods,
                    selectedPod = state.startSheet.selectedPod?.takeIf { it in intent.pods }
                        ?: intent.pods.firstOrNull(),
                ),
            )
        }
    }

    private fun startPortForward() {
        val base = baseRequest ?: return
        val state = _uiState.value
        val podName = state.startSheet.selectedPod ?: return
        val remotePort = state.startSheet.remotePortInput.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return

        val localInput = state.startSheet.localPortInput
        val localPort = if (localInput.isBlank()) {
            null
        } else {
            localInput.toIntOrNull()?.takeIf { it in 0..65_535 } ?: return
        }

        viewModelScope.launch {
            portForwardRepository.createSession(
                request = PodPortForwardRequest(
                    apiServer = base.apiServer,
                    namespace = base.namespace,
                    podName = podName,
                    remotePort = remotePort,
                    localPort = localPort,
                    bearerToken = base.bearerToken,
                ),
            ).onSuccess { session ->
                val sessionId = UUID.randomUUID().toString()
                val managed = ManagedPortForwardSession(
                    id = sessionId,
                    namespace = base.namespace,
                    podName = podName,
                    remotePort = remotePort,
                    session = session,
                    statusJob = Job(),
                )

                activeSessions[sessionId] = managed
                val statusJob = observeSessionStatus(
                    id = sessionId,
                    namespace = base.namespace,
                    podName = podName,
                    remotePort = remotePort,
                    session = session,
                )
                activeSessions[sessionId] = managed.copy(statusJob = statusJob)
                refreshSessions()
                _uiState.update {
                    it.copy(
                        isStartSheetVisible = false,
                        startSheet = it.startSheet.copy(
                            selectedPod = podName,
                            remotePortInput = "",
                            localPortInput = "",
                        ),
                    )
                }
            }
        }
    }

    private fun observeSessionStatus(
        id: String,
        namespace: String,
        podName: String,
        remotePort: Int,
        session: PortForwardSession,
    ): Job {
        return viewModelScope.launch {
            session.statusFlow.collect { status ->
                val existing = activeSessions[id] ?: return@collect
                activeSessions[id] = existing.copy(
                    lastKnownStatus = status,
                    namespace = namespace,
                    podName = podName,
                    remotePort = remotePort,
                )
                refreshSessions()
            }
        }
    }

    private fun closeSession(sessionId: String) {
        val session = activeSessions.remove(sessionId) ?: return
        session.statusJob.cancel()
        session.session.close()
        refreshSessions()
    }

    private fun refreshSessions() {
        _uiState.update { state ->
            state.copy(
                sessions = activeSessions.values.map { managed ->
                    PortForwardSessionUiModel(
                        id = managed.id,
                        namespace = managed.namespace,
                        podName = managed.podName,
                        localPort = managed.session.localPort,
                        remotePort = managed.remotePort,
                        status = managed.lastKnownStatus.toUiStatus(),
                    )
                },
            )
        }
    }

    override fun onCleared() {
        activeSessions.values.forEach { managed ->
            managed.statusJob.cancel()
            managed.session.close()
        }
        activeSessions.clear()
        _uiState.update { it.copy(sessions = emptyList()) }
        super.onCleared()
    }
}

private data class BasePortForwardRequest(
    val apiServer: String,
    val namespace: String,
    val bearerToken: String?,
)

private data class ManagedPortForwardSession(
    val id: String,
    val namespace: String,
    val podName: String,
    val remotePort: Int,
    val session: PortForwardSession,
    val statusJob: Job,
    val lastKnownStatus: PortForwardStatus = PortForwardStatus.Starting,
)

private fun PortForwardStatus.toUiStatus(): PortForwardSessionUiStatus {
    return when (this) {
        PortForwardStatus.Starting -> PortForwardSessionUiStatus.STARTING
        PortForwardStatus.Active -> PortForwardSessionUiStatus.ACTIVE
        is PortForwardStatus.Failed -> PortForwardSessionUiStatus.FAILED
        PortForwardStatus.Closed -> PortForwardSessionUiStatus.CLOSED
    }
}
