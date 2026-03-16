package com.kubedroid.feature.settings.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.connection.ClusterConnectionRepository
import com.kubedroid.core.network.connection.ClusterConnectionState
import com.kubedroid.core.network.kubeconfig.KubeCluster
import com.kubedroid.core.network.kubeconfig.KubeConfig
import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import com.kubedroid.core.network.kubeconfig.KubeContext
import com.kubedroid.core.network.kubeconfig.KubeUser
import dagger.hilt.android.lifecycle.HiltViewModel
import io.kubernetes.client.util.KubeConfig as ClientKubeConfig
import java.io.StringReader
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.yaml.snakeyaml.Yaml

data class ClusterSettingsUiState(
    val clusters: List<ClusterListItemUiModel> = emptyList(),
    val activeContextName: String? = null,
    val connectionState: ClusterConnectionState = ClusterConnectionState.Disconnected,
    val inputMode: AddClusterInputMode = AddClusterInputMode.KUBECONFIG,
    val kubeConfigText: String = "",
    val server: String = "",
    val token: String = "",
    val insecureTlsEnabled: Boolean = false,
    val showInsecureTlsWarningDialog: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

data class ClusterListItemUiModel(
    val contextName: String,
    val serverUrl: String,
)

@HiltViewModel
class ClusterSettingsViewModel @Inject constructor(
    private val kubeConfigRepository: KubeConfigRepository,
    private val clusterConnectionRepository: ClusterConnectionRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "ClusterSettingsVM"
    }

    private val yaml = Yaml()

    private val _uiState = MutableStateFlow(ClusterSettingsUiState())
    val uiState: StateFlow<ClusterSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            clusterConnectionRepository.getState().collect { connectionState ->
                _uiState.update { it.copy(connectionState = connectionState) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val loaded = kubeConfigRepository.load()
            if (loaded.isSuccess) {
                val config = loaded.getOrThrow()
                val serverByClusterName = config.clusters.associate { it.name to it.server }
                val clusterItems = config.contexts.map { context ->
                    ClusterListItemUiModel(
                        contextName = context.name,
                        serverUrl = serverByClusterName[context.cluster] ?: context.cluster,
                    )
                }
                _uiState.update {
                    it.copy(
                        clusters = clusterItems,
                        activeContextName = config.currentContext,
                        errorMessage = null,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        clusters = emptyList(),
                        activeContextName = null,
                        errorMessage = null,
                    )
                }
            }
        }
    }

    fun onInputModeChange(mode: AddClusterInputMode) {
        _uiState.update { it.copy(inputMode = mode) }
    }

    fun onKubeConfigTextChange(value: String) {
        _uiState.update { it.copy(kubeConfigText = value) }
    }

    fun onServerChange(value: String) {
        _uiState.update { it.copy(server = value) }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(token = value) }
    }

    fun onInsecureTlsToggleRequested(enabled: Boolean) {
        _uiState.update { state ->
            when {
                enabled && !state.insecureTlsEnabled ->
                    state.copy(showInsecureTlsWarningDialog = true)
                !enabled ->
                    state.copy(
                        insecureTlsEnabled = false,
                        showInsecureTlsWarningDialog = false,
                    )
                else -> state
            }
        }
    }

    fun confirmEnableInsecureTls() {
        _uiState.update {
            it.copy(
                insecureTlsEnabled = true,
                showInsecureTlsWarningDialog = false,
            )
        }
    }

    fun dismissInsecureTlsWarning() {
        _uiState.update { it.copy(showInsecureTlsWarningDialog = false) }
    }

    fun connectSelectedCluster(
        contextName: String,
        onConnected: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val configResult = kubeConfigRepository.load()
            if (configResult.isFailure) {
                val message = configResult.exceptionOrNull()?.message ?: "Failed to load kubeconfig"
                Log.e(TAG, "connectSelectedCluster load failed: $message", configResult.exceptionOrNull())
                _uiState.update { it.copy(errorMessage = message) }
                return@launch
            }

            val context = configResult.getOrThrow().contexts.firstOrNull { it.name == contextName }
            if (context == null) {
                val message = "Context not found"
                Log.e(TAG, message)
                _uiState.update { it.copy(errorMessage = message) }
                return@launch
            }

            kubeConfigRepository.setActiveContext(contextName)
            _uiState.update { it.copy(activeContextName = contextName) }
            val result = clusterConnectionRepository.connect(context)
            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: "Failed to connect"
                Log.e(TAG, "connectSelectedCluster failed: $message", result.exceptionOrNull())
                _uiState.update { state ->
                    state.copy(errorMessage = message)
                }
            } else {
                onConnected?.invoke()
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            val result = clusterConnectionRepository.disconnect()
            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: "Failed to disconnect"
                Log.e(TAG, "disconnect failed: $message", result.exceptionOrNull())
                _uiState.update { state ->
                    state.copy(errorMessage = message)
                }
            }
        }
    }

    fun deleteCluster(contextName: String) {
        viewModelScope.launch {
            val loaded = kubeConfigRepository.load()
            if (loaded.isFailure) {
                val message = loaded.exceptionOrNull()?.message ?: "Failed to load kubeconfig"
                Log.e(TAG, "deleteCluster load failed: $message", loaded.exceptionOrNull())
                _uiState.update { it.copy(errorMessage = message) }
                return@launch
            }

            val current = loaded.getOrThrow()
            val updated = current.withoutContext(contextName)
            val saveResult = kubeConfigRepository.save(updated)
            if (saveResult.isFailure) {
                val message = saveResult.exceptionOrNull()?.message ?: "Failed to save kubeconfig"
                Log.e(TAG, "deleteCluster save failed: $message", saveResult.exceptionOrNull())
                _uiState.update { it.copy(errorMessage = message) }
                return@launch
            }

            val nextContext = updated.currentContext
            if (nextContext == null) {
                clusterConnectionRepository.disconnect()
            } else {
                updated.contexts.firstOrNull { it.name == nextContext }?.let { next ->
                    clusterConnectionRepository.connect(next)
                }
            }

            refresh()
        }
    }

    fun saveCluster(reconnect: Boolean, onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            val configResult = when (_uiState.value.inputMode) {
                AddClusterInputMode.KUBECONFIG -> parseKubeConfig(_uiState.value.kubeConfigText)
                AddClusterInputMode.MANUAL -> buildManualConfig(
                    existing = kubeConfigRepository.load().getOrNull(),
                    server = _uiState.value.server,
                    token = _uiState.value.token,
                    insecureSkipTlsVerify = _uiState.value.insecureTlsEnabled,
                )
            }

            if (configResult.isFailure) {
                val message = configResult.exceptionOrNull()?.message ?: "Invalid cluster input"
                Log.e(TAG, "saveCluster parse/build failed: $message", configResult.exceptionOrNull())
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = message,
                    )
                }
                return@launch
            }

            val config = configResult.getOrThrow()
            val saveResult = kubeConfigRepository.save(config)
            if (saveResult.isFailure) {
                val message = saveResult.exceptionOrNull()?.message ?: "Failed to save kubeconfig"
                Log.e(TAG, "saveCluster save failed: $message", saveResult.exceptionOrNull())
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = message,
                    )
                }
                return@launch
            }

            val activeName = config.currentContext
            if (reconnect && activeName != null) {
                config.contexts.firstOrNull { it.name == activeName }?.let { activeContext ->
                    val connectResult = clusterConnectionRepository.connect(activeContext)
                    if (connectResult.isFailure) {
                        val message = connectResult.exceptionOrNull()?.message ?: "Failed to connect"
                        Log.e(TAG, "saveCluster connect failed: $message", connectResult.exceptionOrNull())
                        _uiState.update {
                            it.copy(errorMessage = message)
                        }
                    }
                }
            }

            _uiState.update {
                it.copy(
                    isSaving = false,
                    kubeConfigText = "",
                    server = "",
                    token = "",
                    insecureTlsEnabled = false,
                    showInsecureTlsWarningDialog = false,
                    inputMode = AddClusterInputMode.KUBECONFIG,
                )
            }
            refresh()
            onComplete()
        }
    }

    private fun parseKubeConfig(raw: String): Result<KubeConfig> = runCatching {
        if (raw.isBlank()) {
            error("Kubeconfig is empty")
        }

        ClientKubeConfig.loadKubeConfig(StringReader(raw))

        @Suppress("UNCHECKED_CAST")
        val root = yaml.load<Any?>(raw) as? Map<String, Any?> ?: emptyMap()

        val contexts = (root["contexts"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { entry ->
            val name = entry["name"] as? String ?: return@mapNotNull null
            val contextMap = entry["context"] as? Map<String, Any?> ?: return@mapNotNull null
            val cluster = contextMap["cluster"] as? String ?: return@mapNotNull null
            val user = contextMap["user"] as? String ?: return@mapNotNull null
            val namespace = contextMap["namespace"] as? String
            KubeContext(name = name, cluster = cluster, user = user, namespace = namespace)
        }

        val clusters = (root["clusters"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { entry ->
            val name = entry["name"] as? String ?: return@mapNotNull null
            val clusterMap = entry["cluster"] as? Map<String, Any?> ?: return@mapNotNull null
            val server = clusterMap["server"] as? String ?: return@mapNotNull null
            KubeCluster(name = name, server = server, raw = clusterMap)
        }

        val users = (root["users"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { entry ->
            val name = entry["name"] as? String ?: return@mapNotNull null
            val userMap = entry["user"] as? Map<String, Any?> ?: emptyMap()
            KubeUser(name = name, raw = userMap)
        }

        val currentContext = root["current-context"] as? String ?: contexts.firstOrNull()?.name

        KubeConfig(
            contexts = contexts,
            clusters = clusters,
            users = users,
            currentContext = currentContext,
        )
    }

    private fun buildManualConfig(
        existing: KubeConfig?,
        server: String,
        token: String,
        insecureSkipTlsVerify: Boolean,
    ): Result<KubeConfig> = runCatching {
        if (server.isBlank()) {
            error("Server URL is required")
        }
        if (token.isBlank()) {
            error("Token is required")
        }

        val base = existing ?: KubeConfig(
            contexts = emptyList(),
            clusters = emptyList(),
            users = emptyList(),
            currentContext = null,
        )

        val hostPart = server
            .substringAfter("://", server)
            .substringBefore('/')
            .ifBlank { "cluster" }
            .replace(Regex("[^a-zA-Z0-9-]"), "-")

        var contextName = hostPart
        var index = 1
        val existingNames = base.contexts.map { it.name }.toSet()
        while (contextName in existingNames) {
            contextName = "$hostPart-$index"
            index += 1
        }

        val clusterName = "$contextName-cluster"
        val userName = "$contextName-user"

        val updatedContexts = base.contexts + KubeContext(
            name = contextName,
            cluster = clusterName,
            user = userName,
            namespace = "default",
        )
        val updatedClusters = base.clusters + KubeCluster(
            name = clusterName,
            server = server,
            raw = mapOf("server" to server, "insecure-skip-tls-verify" to insecureSkipTlsVerify),
        )
        val updatedUsers = base.users + KubeUser(
            name = userName,
            raw = mapOf("token" to token),
        )

        KubeConfig(
            contexts = updatedContexts,
            clusters = updatedClusters,
            users = updatedUsers,
            currentContext = contextName,
        )
    }
}

private fun KubeConfig.withoutContext(contextName: String): KubeConfig {
    val remainingContexts = contexts.filterNot { it.name == contextName }
    val clusterNamesInUse = remainingContexts.map { it.cluster }.toSet()
    val userNamesInUse = remainingContexts.map { it.user }.toSet()

    val nextCurrent = when {
        currentContext == contextName -> remainingContexts.firstOrNull()?.name
        remainingContexts.any { it.name == currentContext } -> currentContext
        else -> remainingContexts.firstOrNull()?.name
    }

    return copy(
        contexts = remainingContexts,
        clusters = clusters.filter { it.name in clusterNamesInUse },
        users = users.filter { it.name in userNamesInUse },
        currentContext = nextCurrent,
    )
}
