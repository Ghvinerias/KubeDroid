package com.kubedroid.feature.network.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.namespace.NamespaceStore
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import com.kubedroid.core.network.networking.Ingress
import com.kubedroid.core.network.networking.IngressTls
import com.kubedroid.core.network.networking.NetworkPolicy
import com.kubedroid.core.network.networking.NetworkRepository
import com.kubedroid.core.network.pods.NamespaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val namespaceRepository: NamespaceRepository,
    private val namespaceStore: NamespaceStore,
) : ViewModel() {

    private var currentDetailKey: IngressLookupKey? = null

    private val _uiState = MutableStateFlow(NetworkUiState())
    val uiState: StateFlow<NetworkUiState> = _uiState.asStateFlow()

    private val _ingressDetailState = MutableStateFlow(IngressDetailState())
    val ingressDetailState: StateFlow<IngressDetailState> = _ingressDetailState.asStateFlow()

    init {
        observeSelectedNamespace()
        refresh()
    }

    fun onTabSelected(tab: NetworkTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun onNamespaceChange(namespace: String) {
        val normalized = namespace.trim().ifEmpty { DEFAULT_NAMESPACE }
        viewModelScope.launch {
            namespaceStore.setNamespace(normalized)
        }
    }

    fun refresh() {
        loadNamespaces()
        val namespace = _uiState.value.namespace
        loadIngresses(namespace)
        loadNetworkPolicies(namespace)
    }

    private fun loadNamespaces() {
        viewModelScope.launch {
            namespaceRepository.listNamespaces().fold(
                onSuccess = { namespaces ->
                    val normalized = namespaces
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                    _uiState.update { current ->
                        current.copy(
                            namespaces = normalized,
                            namespace = if (normalized.contains(current.namespace) || current.namespace.isAllNamespacesSelection()) {
                                current.namespace
                            } else {
                                DEFAULT_NAMESPACE
                            },
                        )
                    }
                    val resolvedNamespace = _uiState.value.namespace
                    if (resolvedNamespace !in normalized && !resolvedNamespace.isAllNamespacesSelection() && normalized.isNotEmpty()) {
                        val fallbackNamespace = if (DEFAULT_NAMESPACE in normalized) {
                            DEFAULT_NAMESPACE
                        } else {
                            normalized.first()
                        }
                        viewModelScope.launch {
                            namespaceStore.setNamespace(fallbackNamespace)
                        }
                    }
                },
                onFailure = {
                    _uiState.update { current ->
                        current.copy(namespaces = emptyList())
                    }
                },
            )
        }
    }

    private fun observeSelectedNamespace() {
        viewModelScope.launch {
            namespaceStore.selectedNamespace
                .distinctUntilChanged()
                .collect { namespace ->
                    _uiState.update { it.copy(namespace = namespace) }
                    loadIngresses(namespace)
                    loadNetworkPolicies(namespace)
                }
        }
    }

    fun openIngressDetail(namespace: String, ingressName: String) {
        val key = IngressLookupKey(namespace = namespace, ingressName = ingressName).normalized()
        currentDetailKey = key
        _ingressDetailState.update {
            it.copy(
                namespace = key.namespace,
                ingressName = key.ingressName,
                contentState = IngressDetailContentState.Loading,
            )
        }
        loadIngressDetail(key)
    }

    fun refreshIngressDetail() {
        val key = currentDetailKey ?: return
        _ingressDetailState.update {
            it.copy(contentState = IngressDetailContentState.Loading)
        }
        loadIngressDetail(key)
    }

    private fun loadIngresses(namespace: String) {
        _uiState.update { it.copy(ingressState = NetworkListState.Loading) }

        viewModelScope.launch {
            networkRepository.listIngresses(namespace).fold(
                onSuccess = { ingresses ->
                    _uiState.update {
                        it.copy(
                            ingresses = ingresses.toIngressListUi(),
                            ingressState = if (ingresses.isEmpty()) NetworkListState.Empty else NetworkListState.Success,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(ingressState = NetworkListState.Error(cause = throwable))
                    }
                },
            )
        }
    }

    private fun loadNetworkPolicies(namespace: String) {
        _uiState.update { it.copy(networkPolicyState = NetworkListState.Loading) }

        viewModelScope.launch {
            networkRepository.listNetworkPolicies(namespace).fold(
                onSuccess = { policies ->
                    _uiState.update {
                        it.copy(
                            networkPolicies = policies.toNetworkPolicyListUi(),
                            networkPolicyState = if (policies.isEmpty()) {
                                NetworkListState.Empty
                            } else {
                                NetworkListState.Success
                            },
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(networkPolicyState = NetworkListState.Error(cause = throwable))
                    }
                },
            )
        }
    }

    private fun loadIngressDetail(key: IngressLookupKey) {
        viewModelScope.launch {
            networkRepository.listIngresses(key.namespace).fold(
                onSuccess = { ingresses ->
                    val ingress = ingresses.firstOrNull { it.name == key.ingressName }
                    if (ingress == null) {
                        _ingressDetailState.update {
                            it.copy(
                                namespace = key.namespace,
                                ingressName = key.ingressName,
                                contentState = IngressDetailContentState.Error(cause = null),
                            )
                        }
                    } else {
                        _ingressDetailState.update {
                            it.copy(
                                namespace = key.namespace,
                                ingressName = key.ingressName,
                                contentState = IngressDetailContentState.Success(
                                    detail = ingress.toIngressDetailUi(),
                                ),
                            )
                        }
                    }
                },
                onFailure = { throwable ->
                    _ingressDetailState.update {
                        it.copy(
                            namespace = key.namespace,
                            ingressName = key.ingressName,
                            contentState = IngressDetailContentState.Error(cause = throwable),
                        )
                    }
                },
            )
        }
    }
}

private fun List<Ingress>.toIngressListUi(): List<IngressListItemUi> = map { ingress ->
    val primaryRule = ingress.rules.firstOrNull { !it.host.isNullOrBlank() && it.paths.isNotEmpty() }
    val primaryHost = primaryRule?.host
    val primaryPath = primaryRule?.paths?.firstOrNull()?.path.orEmpty().ifBlank { "/" }
    val copyUrl = if (primaryHost == null) {
        null
    } else {
        val scheme = if (ingress.isTlsCoveredForHost(primaryHost)) "https" else "http"
        "$scheme://$primaryHost${normalizedPath(primaryPath)}"
    }

    IngressListItemUi(
        name = ingress.name,
        namespace = ingress.namespace,
        primaryHost = primaryHost,
        pathCount = ingress.rules.sumOf { it.paths.size },
        hasTlsForPrimaryHost = ingress.isTlsCoveredForHost(primaryHost),
        copyUrl = copyUrl,
    )
}

private fun List<NetworkPolicy>.toNetworkPolicyListUi(): List<NetworkPolicyListItemUi> = map { policy ->
    NetworkPolicyListItemUi(
        name = policy.name,
        namespace = policy.namespace,
        podSelectorSummary = policy.podSelector.toPodSelectorSummary(),
        allowsTraffic = policy.ingressRules.isNotEmpty() || policy.egressRules.isNotEmpty(),
    )
}

private fun Map<String, String>.toPodSelectorSummary(): String {
    if (isEmpty()) return ""
    return entries
        .sortedBy { it.key }
        .joinToString(separator = ", ") { entry -> "${entry.key}=${entry.value}" }
}

private fun Ingress.isTlsCoveredForHost(host: String?): Boolean {
    if (tls.isEmpty() || host.isNullOrBlank()) return false
    return tls.any { tlsEntry -> tlsEntry.coversHost(host) }
}

private fun IngressTls.coversHost(host: String): Boolean {
    if (hosts.isEmpty()) return true
    return hosts.any { tlsHost ->
        val normalizedTlsHost = tlsHost.trim().lowercase()
        val normalizedHost = host.trim().lowercase()
        normalizedTlsHost == "*" ||
            normalizedTlsHost == normalizedHost ||
            (normalizedTlsHost.startsWith("*.") && normalizedHost.matchesWildcardHost(normalizedTlsHost))
    }
}

private fun String.matchesWildcardHost(wildcardHost: String): Boolean {
    val suffix = wildcardHost.removePrefix("*.")
    return endsWith(".$suffix") && this != suffix
}

private fun normalizedPath(path: String): String {
    return if (path.startsWith('/')) path else "/$path"
}
