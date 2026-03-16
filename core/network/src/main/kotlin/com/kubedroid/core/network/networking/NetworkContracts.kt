package com.kubedroid.core.network.networking

/**
 * Core ingress model used by list/watch operations.
 */
data class Ingress(
    val name: String,
    val namespace: String,
    val ingressClass: String?,
    val rules: List<IngressRule>,
    val tls: List<IngressTls>,
)

/**
 * Core ingress rule model.
 */
data class IngressRule(
    val host: String?,
    val paths: List<IngressPath>,
)

/**
 * Core ingress path model.
 */
data class IngressPath(
    val path: String?,
    val pathType: String?,
    val serviceName: String?,
    val servicePort: String?,
)

/**
 * Core ingress TLS model.
 */
data class IngressTls(
    val hosts: List<String>,
    val secretName: String?,
)

/**
 * Core network policy model used by list operations.
 */
data class NetworkPolicy(
    val name: String,
    val namespace: String,
    val podSelector: Map<String, String>,
    val ingressRules: List<NetworkPolicyRule>,
    val egressRules: List<NetworkPolicyRule>,
)

/**
 * Core network policy rule model.
 */
data class NetworkPolicyRule(
    val peers: List<NetworkPolicyPeer>,
    val ports: List<NetworkPolicyPort>,
)

/**
 * Core network policy peer selector model.
 */
data class NetworkPolicyPeer(
    val podSelector: Map<String, String>?,
    val namespaceSelector: Map<String, String>?,
    val ipBlockCidr: String?,
)

/**
 * Core network policy port model.
 */
data class NetworkPolicyPort(
    val protocol: String?,
    val port: String?,
)
