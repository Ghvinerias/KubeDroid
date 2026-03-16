package com.kubedroid.feature.network.ui

import com.kubedroid.core.network.networking.Ingress

const val DEFAULT_NAMESPACE = "default"

enum class NetworkTab {
    INGRESSES,
    NETWORK_POLICIES,
}

data class IngressListItemUi(
    val name: String,
    val namespace: String,
    val primaryHost: String?,
    val pathCount: Int,
    val hasTlsForPrimaryHost: Boolean,
    val copyUrl: String?,
)

data class IngressPathRouteUi(
    val path: String,
    val serviceName: String,
    val servicePort: String?,
)

data class IngressHostRouteUi(
    val host: String?,
    val paths: List<IngressPathRouteUi>,
)

data class IngressDetailUi(
    val name: String,
    val namespace: String,
    val routes: List<IngressHostRouteUi>,
)

data class NetworkPolicyListItemUi(
    val name: String,
    val namespace: String,
    val podSelectorSummary: String,
    val allowsTraffic: Boolean,
)

sealed interface NetworkListState {
    data object Loading : NetworkListState
    data object Success : NetworkListState
    data object Empty : NetworkListState
    data class Error(val cause: Throwable?) : NetworkListState
}

data class NetworkUiState(
    val selectedTab: NetworkTab = NetworkTab.INGRESSES,
    val namespace: String = DEFAULT_NAMESPACE,
    val namespaces: List<String> = emptyList(),
    val ingresses: List<IngressListItemUi> = emptyList(),
    val networkPolicies: List<NetworkPolicyListItemUi> = emptyList(),
    val ingressState: NetworkListState = NetworkListState.Loading,
    val networkPolicyState: NetworkListState = NetworkListState.Loading,
)

sealed interface IngressDetailContentState {
    data object Loading : IngressDetailContentState
    data class Error(val cause: Throwable?) : IngressDetailContentState
    data class Success(val detail: IngressDetailUi) : IngressDetailContentState
}

data class IngressDetailState(
    val namespace: String = DEFAULT_NAMESPACE,
    val ingressName: String = "",
    val contentState: IngressDetailContentState = IngressDetailContentState.Loading,
)

data class IngressLookupKey(
    val namespace: String,
    val ingressName: String,
)

internal fun IngressLookupKey.normalized(): IngressLookupKey {
    return IngressLookupKey(
        namespace = namespace.trim().ifEmpty { DEFAULT_NAMESPACE },
        ingressName = ingressName.trim(),
    )
}

internal fun Ingress.toIngressDetailUi(): IngressDetailUi {
    return IngressDetailUi(
        name = name,
        namespace = namespace,
        routes = rules.map { rule ->
            IngressHostRouteUi(
                host = rule.host,
                paths = rule.paths.map { path ->
                    IngressPathRouteUi(
                        path = path.path.orEmpty().ifBlank { "/" },
                        serviceName = path.serviceName.orEmpty(),
                        servicePort = path.servicePort,
                    )
                },
            )
        },
    )
}
