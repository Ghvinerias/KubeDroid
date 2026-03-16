package com.kubedroid.core.network.kubeconfig

/**
 * Named Kubernetes context that binds cluster and user, with an optional namespace.
 */
data class KubeContext(
    val name: String,
    val cluster: String,
    val user: String,
    val namespace: String? = null,
)

/**
 * Parsed kubeconfig contract containing contexts, clusters, users, and active context.
 */
data class KubeConfig(
    val contexts: List<KubeContext>,
    val clusters: List<KubeCluster> = emptyList(),
    val users: List<KubeUser> = emptyList(),
    val currentContext: String?,
)

/**
 * Named Kubernetes API cluster endpoint entry from kubeconfig.
 */
data class KubeCluster(
    val name: String,
    val server: String,
    val raw: Map<String, Any?> = emptyMap(),
)

/**
 * Named kubeconfig user/auth identity entry.
 */
data class KubeUser(
    val name: String,
    val raw: Map<String, Any?> = emptyMap(),
)
