package com.kubedroid.core.network.networking

import com.google.gson.reflect.TypeToken
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import io.kubernetes.client.custom.IntOrString
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.NetworkingV1Api
import io.kubernetes.client.openapi.models.V1Ingress
import io.kubernetes.client.openapi.models.V1NetworkPolicy
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Watch
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kubernetes API-backed [NetworkRepository] implementation.
 */
class NetworkRepositoryImpl(
    private val apiClientProvider: () -> ApiClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchReconnectInitialBackoffMillis: Long = 1_000L,
    private val watchReconnectMaxBackoffMillis: Long = 30_000L,
    private val networkApiFactory: NetworkApiFactory = NetworkApiFactory.Default,
) : NetworkRepository {

    constructor(
        kubeConfigPath: Path,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        watchReconnectInitialBackoffMillis: Long = 1_000L,
        watchReconnectMaxBackoffMillis: Long = 30_000L,
        networkApiFactory: NetworkApiFactory = NetworkApiFactory.Default,
    ) : this(
        apiClientProvider = { buildNetworkApiClient(kubeConfigPath) },
        ioDispatcher = ioDispatcher,
        watchReconnectInitialBackoffMillis = watchReconnectInitialBackoffMillis,
        watchReconnectMaxBackoffMillis = watchReconnectMaxBackoffMillis,
        networkApiFactory = networkApiFactory,
    )

    override suspend fun listIngresses(namespace: String): Result<List<Ingress>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = namespace.trim().ifEmpty {
                throw IllegalArgumentException("Namespace must not be empty")
            }
            networkApiFactory
                .create(apiClientProvider)
                .listIngressesSnapshot(normalizedNamespace)
                .ingresses
                .sortedIngresses()
        }
    }

    override fun watchIngresses(namespace: String): Flow<Result<List<Ingress>>> = callbackFlow {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isEmpty()) {
            trySend(Result.failure(IllegalArgumentException("Namespace must not be empty")))
            close()
            return@callbackFlow
        }

        val watcherJob = launch(ioDispatcher) {
            var reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)
            var resourceVersion: String? = null
            var shouldReconnect = true

            while (isActive && shouldReconnect) {
                val networkApi = networkApiFactory.create(apiClientProvider)
                try {
                    val initial = networkApi.listIngressesSnapshot(normalizedNamespace)
                    resourceVersion = initial.resourceVersion
                    val state = initial.ingresses.associateBy { it.namespacedKey() }.toMutableMap()
                    trySend(Result.success(state.sortedIngresses()))

                    networkApi.openIngressWatch(normalizedNamespace, resourceVersion).use { watchSession ->
                        reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)

                        for (event in watchSession) {
                            if (!isActive) break
                            resourceVersion = event.resourceVersion ?: resourceVersion

                            when (event.type?.uppercase()) {
                                "ADDED", "MODIFIED" -> {
                                    event.ingress?.let { state[it.namespacedKey()] = it }
                                    trySend(Result.success(state.sortedIngresses()))
                                }

                                "DELETED" -> {
                                    event.ingress?.let { state.remove(it.namespacedKey()) }
                                    trySend(Result.success(state.sortedIngresses()))
                                }

                                "BOOKMARK" -> Unit
                                else -> Unit
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    trySend(Result.failure(throwable))
                    if ((throwable as? ApiException)?.code == 403) {
                        shouldReconnect = false
                        break
                    }
                    delay(reconnectDelayMillis)
                    reconnectDelayMillis = (reconnectDelayMillis * 2).coerceAtMost(
                        watchReconnectMaxBackoffMillis.coerceAtLeast(reconnectDelayMillis),
                    )
                }
            }
        }

        awaitClose { watcherJob.cancel() }
    }.flowOn(ioDispatcher)

    override suspend fun listNetworkPolicies(namespace: String): Result<List<NetworkPolicy>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = namespace.trim().ifEmpty {
                throw IllegalArgumentException("Namespace must not be empty")
            }
            networkApiFactory
                .create(apiClientProvider)
                .listNetworkPolicies(normalizedNamespace)
                .sortedBy { it.name }
        }
    }

    override suspend fun getServiceUrl(service: String, namespace: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = namespace.requireNamespace()
            val normalizedService = service.requireServiceName()
            val ingresses = networkApiFactory
                .create(apiClientProvider)
                .listIngressesSnapshot(normalizedNamespace)
                .ingresses

            if (ingresses.isEmpty()) {
                throw IllegalStateException(
                    "No ingress resources found in namespace \"$normalizedNamespace\". " +
                        "No ingress controller route is available for service \"$normalizedService\".",
                )
            }

            val candidates = ingresses.flatMap { ingress ->
                ingress.rules.flatMap { rule ->
                    rule.paths
                        .filter { it.serviceName == normalizedService }
                        .map { path ->
                            RouteCandidate(
                                ingressName = ingress.name,
                                ingressClass = ingress.ingressClass.trimToNull(),
                                host = rule.host.trimToNull(),
                                path = path.path,
                                isDefaultBackend = path.pathType == DEFAULT_BACKEND_PATH_TYPE,
                                tlsCovered = ingress.isTlsCoveredForHost(rule.host),
                            )
                        }
                }
            }

            if (candidates.isEmpty()) {
                throw IllegalStateException(
                    "No ingress route matches service \"$normalizedService\" in namespace \"$normalizedNamespace\".",
                )
            }

            val distinctClasses = candidates
                .map { it.ingressClass ?: "<default>" }
                .distinct()
            if (distinctClasses.size > 1) {
                throw IllegalStateException(
                    "Service \"$normalizedService\" is routed by multiple ingress classes: " +
                        distinctClasses.sorted().joinToString(", "),
                )
            }

            val selected = candidates.sortedWith(routeCandidateComparator).first()
            val host = selected.host
            if (host == null) {
                if (selected.isDefaultBackend) {
                    throw IllegalStateException(
                        "Service \"$normalizedService\" is only reachable via ingress default backend " +
                            "without explicit host/path routing.",
                    )
                }
                throw IllegalStateException(
                    "Ingress route for service \"$normalizedService\" does not define a host.",
                )
            }

            val normalizedPath = selected.path.toNormalizedUrlPath()
            val scheme = if (selected.tlsCovered) "https" else "http"
            "$scheme://$host$normalizedPath"
        }
    }
}

fun interface NetworkApiFactory {
    fun create(apiClientProvider: () -> ApiClient): NetworkApi

    data object Default : NetworkApiFactory {
        override fun create(apiClientProvider: () -> ApiClient): NetworkApi = KubernetesNetworkApi(apiClientProvider)
    }
}

interface NetworkApi {
    fun listIngressesSnapshot(namespace: String): IngressListSnapshot
    fun openIngressWatch(namespace: String, resourceVersion: String?): IngressWatchSession
    fun listNetworkPolicies(namespace: String): List<NetworkPolicy>
}

data class IngressListSnapshot(
    val ingresses: List<Ingress>,
    val resourceVersion: String?,
)

data class IngressWatchEvent(
    val type: String?,
    val ingress: Ingress?,
    val resourceVersion: String?,
)

interface IngressWatchSession : Iterable<IngressWatchEvent>, AutoCloseable

private class KubernetesNetworkApi(
    private val apiClientProvider: () -> ApiClient,
) : NetworkApi {

    override fun listIngressesSnapshot(namespace: String): IngressListSnapshot {
        val response = if (namespace.isAllNamespacesSelection()) {
            networkingV1Api().listIngressForAllNamespaces().execute()
        } else {
            networkingV1Api().listNamespacedIngress(namespace).execute()
        }
        val fallbackNamespace = if (namespace.isAllNamespacesSelection()) null else namespace

        return IngressListSnapshot(
            ingresses = response
                .items
                .orEmpty()
                .map { it.toDomainIngress(fallbackNamespace) },
            resourceVersion = response.metadata?.resourceVersion,
        )
    }

    override fun openIngressWatch(namespace: String, resourceVersion: String?): IngressWatchSession {
        val api = networkingV1Api()
        val call = if (namespace.isAllNamespacesSelection()) {
            val requestBuilder = api.listIngressForAllNamespaces()
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        } else {
            val requestBuilder = api.listNamespacedIngress(namespace)
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        }
        val watch: Watch<V1Ingress> = Watch.createWatch(
            apiClientProvider(),
            call,
            INGRESS_WATCH_TYPE,
        )
        return KubernetesIngressWatchSession(watch)
    }

    override fun listNetworkPolicies(namespace: String): List<NetworkPolicy> {
        val response = if (namespace.isAllNamespacesSelection()) {
            networkingV1Api().listNetworkPolicyForAllNamespaces().execute()
        } else {
            networkingV1Api().listNamespacedNetworkPolicy(namespace).execute()
        }
        val fallbackNamespace = if (namespace.isAllNamespacesSelection()) null else namespace
        return response
            .items
            .orEmpty()
            .map { it.toDomainNetworkPolicy(fallbackNamespace) }
    }

    private fun networkingV1Api(): NetworkingV1Api = NetworkingV1Api(apiClientProvider())
}

private class KubernetesIngressWatchSession(
    private val watch: Watch<V1Ingress>,
) : IngressWatchSession {
    override fun iterator(): Iterator<IngressWatchEvent> {
        val delegate = watch.iterator()
        return object : Iterator<IngressWatchEvent> {
            override fun hasNext(): Boolean = delegate.hasNext()

            override fun next(): IngressWatchEvent {
                val event = delegate.next()
                val ingress = event.`object`
                return IngressWatchEvent(
                    type = event.type,
                    ingress = ingress?.toDomainIngress(),
                    resourceVersion = ingress?.metadata?.resourceVersion,
                )
            }
        }
    }

    override fun close() {
        watch.close()
    }
}

internal fun V1Ingress.toDomainIngress(fallbackNamespace: String? = null): Ingress {
    val metadata = metadata
    val spec = spec
    val explicitRules = spec
        ?.rules
        .orEmpty()
        .mapNotNull { rule ->
            val paths = rule.http?.paths.orEmpty().mapNotNull { path -> path.toDomainIngressPath() }.sortedIngressPaths()
            if (paths.isEmpty()) {
                null
            } else {
                IngressRule(
                    host = rule.host.trimToNull(),
                    paths = paths,
                )
            }
        }
    val defaultBackendPath = spec?.defaultBackend?.toDomainIngressPath(pathType = DEFAULT_BACKEND_PATH_TYPE)
    val mergedRules = if (defaultBackendPath != null) {
        explicitRules + IngressRule(
            host = null,
            paths = listOf(defaultBackendPath),
        )
    } else {
        explicitRules
    }

    return Ingress(
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace ?: fallbackNamespace.orEmpty(),
        ingressClass = spec?.ingressClassName.trimToNull()
            ?: metadata?.annotations?.get(LEGACY_INGRESS_CLASS_ANNOTATION).trimToNull(),
        rules = mergedRules.sortedIngressRules(),
        tls = spec
            ?.tls
            .orEmpty()
            .map { tls ->
                IngressTls(
                    hosts = tls.hosts.orEmpty().mapNotNull { it.trimToNull() }.distinct().sorted(),
                    secretName = tls.secretName.trimToNull(),
                )
            }
            .sortedIngressTls(),
    )
}

private fun io.kubernetes.client.openapi.models.V1HTTPIngressPath.toDomainIngressPath(): IngressPath? {
    return backend.toDomainIngressPath(
        path = path.trimToNull(),
        pathType = pathType.trimToNull(),
    )
}

private fun io.kubernetes.client.openapi.models.V1IngressBackend.toDomainIngressPath(
    path: String? = null,
    pathType: String? = null,
): IngressPath? {
    val serviceName = service?.name.trimToNull() ?: return null
    return IngressPath(
        path = path,
        pathType = pathType,
        serviceName = serviceName,
        servicePort = service?.port.toDomainServicePort(),
    )
}

private fun io.kubernetes.client.openapi.models.V1ServiceBackendPort?.toDomainServicePort(): String? {
    if (this == null) return null
    return name.trimToNull() ?: number?.toString()
}

private fun V1NetworkPolicy.toDomainNetworkPolicy(fallbackNamespace: String? = null): NetworkPolicy {
    val metadata = metadata
    val spec = spec
    return NetworkPolicy(
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace ?: fallbackNamespace.orEmpty(),
        podSelector = spec?.podSelector?.matchLabels.orEmpty().toSortedMap(),
        ingressRules = spec
            ?.ingress
            .orEmpty()
            .map { rule ->
                NetworkPolicyRule(
                    peers = rule.from.orEmpty().map { peer ->
                        NetworkPolicyPeer(
                            podSelector = peer.podSelector?.matchLabels.orEmpty().toSortedMap().ifEmpty { null },
                            namespaceSelector = peer.namespaceSelector?.matchLabels.orEmpty().toSortedMap().ifEmpty { null },
                            ipBlockCidr = peer.ipBlock?.cidr.trimToNull(),
                        )
                    },
                    ports = rule.ports.orEmpty().map { port ->
                        NetworkPolicyPort(
                            protocol = port.protocol?.toString().trimToNull(),
                            port = port.port.toPortString(),
                        )
                    },
                )
            },
        egressRules = spec
            ?.egress
            .orEmpty()
            .map { rule ->
                NetworkPolicyRule(
                    peers = rule.to.orEmpty().map { peer ->
                        NetworkPolicyPeer(
                            podSelector = peer.podSelector?.matchLabels.orEmpty().toSortedMap().ifEmpty { null },
                            namespaceSelector = peer.namespaceSelector?.matchLabels.orEmpty().toSortedMap().ifEmpty { null },
                            ipBlockCidr = peer.ipBlock?.cidr.trimToNull(),
                        )
                    },
                    ports = rule.ports.orEmpty().map { port ->
                        NetworkPolicyPort(
                            protocol = port.protocol?.toString().trimToNull(),
                            port = port.port.toPortString(),
                        )
                    },
                )
            },
    )
}

private fun IntOrString?.toPortString(): String? = this?.toString()?.trimToNull()

private fun Map<String, Ingress>.sortedIngresses(): List<Ingress> = values.toList().sortedIngresses()

private fun List<Ingress>.sortedIngresses(): List<Ingress> = sortedBy { it.name }

private fun Ingress.namespacedKey(): String = "${namespace.trim()}/${name.trim()}"

private fun List<IngressRule>.sortedIngressRules(): List<IngressRule> {
    return sortedWith(
        compareBy<IngressRule>({ it.host == null }, { it.host.orEmpty() }),
    )
}

private fun List<IngressPath>.sortedIngressPaths(): List<IngressPath> {
    return sortedWith(
        compareBy<IngressPath>(
            { it.path.toNormalizedUrlPath() },
            { it.serviceName.orEmpty() },
            { it.servicePort.orEmpty() },
            { it.pathType.orEmpty() },
        ),
    )
}

private fun List<IngressTls>.sortedIngressTls(): List<IngressTls> {
    return sortedWith(
        compareBy<IngressTls>(
            { it.hosts.joinToString(",") },
            { it.secretName.orEmpty() },
        ),
    )
}

internal fun Ingress.isTlsCoveredForHost(host: String?): Boolean {
    if (tls.isEmpty()) return false
    val normalizedHost = host.trimToNull()?.lowercase()
    return tls.any { entry ->
        if (entry.hosts.isEmpty()) return@any true
        if (normalizedHost == null) {
            entry.hosts.any { it == "*" }
        } else {
            entry.hosts.any { tlsHost ->
                val candidate = tlsHost.lowercase()
                candidate == "*" ||
                    candidate == normalizedHost ||
                    (candidate.startsWith("*.") && normalizedHost.matchesWildcardHost(candidate))
            }
        }
    }
}

private fun String.matchesWildcardHost(wildcardHost: String): Boolean {
    val suffix = wildcardHost.removePrefix("*.")
    if (!endsWith(".$suffix")) return false
    return this != suffix
}

private fun String?.toNormalizedUrlPath(): String {
    val trimmed = this.trimToNull() ?: "/"
    return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
}

private fun String?.trimToNull(): String? {
    val normalized = this?.trim().orEmpty()
    return normalized.ifEmpty { null }
}

private fun String.requireNamespace(): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "Namespace must not be empty" }
    return normalized
}

private fun String.requireServiceName(): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "Service name must not be empty" }
    return normalized
}

private fun buildNetworkApiClient(kubeConfigPath: Path): ApiClient {
    if (!Files.exists(kubeConfigPath)) {
        throw IllegalStateException("Kubeconfig file not found at $kubeConfigPath")
    }
    val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    val kubeConfig = KubeConfig.loadKubeConfig(StringReader(raw))
    return ClientBuilder.kubeconfig(kubeConfig)
        .build()
        .setLenientOnJson(true)
}

private data class RouteCandidate(
    val ingressName: String,
    val ingressClass: String?,
    val host: String?,
    val path: String?,
    val isDefaultBackend: Boolean,
    val tlsCovered: Boolean,
)

private val routeCandidateComparator = compareBy<RouteCandidate>(
    { it.host == null },
    { !it.tlsCovered },
    { -it.path.toNormalizedUrlPath().length },
    { it.path.toNormalizedUrlPath() },
    { it.ingressName },
)

private const val DEFAULT_BACKEND_PATH_TYPE = "DefaultBackend"
private const val LEGACY_INGRESS_CLASS_ANNOTATION = "kubernetes.io/ingress.class"
private const val WATCH_TIMEOUT_SECONDS = 30

private val INGRESS_WATCH_TYPE = object : TypeToken<Watch.Response<V1Ingress>>() {}.type
