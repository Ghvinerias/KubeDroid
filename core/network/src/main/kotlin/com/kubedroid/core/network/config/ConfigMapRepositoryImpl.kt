package com.kubedroid.core.network.config

import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ConfigMap
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Watch
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
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
 * Kubernetes API-backed [ConfigMapRepository] implementation.
 */
class ConfigMapRepositoryImpl(
    private val apiClientProvider: () -> ApiClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchReconnectInitialBackoffMillis: Long = 1_000L,
    private val watchReconnectMaxBackoffMillis: Long = 30_000L,
    private val configMapApiFactory: ConfigMapApiFactory = ConfigMapApiFactory.Default,
) : ConfigMapRepository {

    constructor(
        kubeConfigPath: Path,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        watchReconnectInitialBackoffMillis: Long = 1_000L,
        watchReconnectMaxBackoffMillis: Long = 30_000L,
        configMapApiFactory: ConfigMapApiFactory = ConfigMapApiFactory.Default,
    ) : this(
        apiClientProvider = { buildConfigMapApiClient(kubeConfigPath) },
        ioDispatcher = ioDispatcher,
        watchReconnectInitialBackoffMillis = watchReconnectInitialBackoffMillis,
        watchReconnectMaxBackoffMillis = watchReconnectMaxBackoffMillis,
        configMapApiFactory = configMapApiFactory,
    )

    override suspend fun list(namespace: String): Result<List<ConfigMap>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = namespace.trim().ifEmpty {
                throw IllegalArgumentException("Namespace must not be empty")
            }
            configMapApiFactory.create(apiClientProvider).listConfigMapsSnapshot(normalizedNamespace).configMaps
        }
    }

    override fun watch(namespace: String): Flow<Result<List<ConfigMap>>> = callbackFlow {
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
                val configMapApi = configMapApiFactory.create(apiClientProvider)
                try {
                    val initial = configMapApi.listConfigMapsSnapshot(normalizedNamespace)
                    resourceVersion = initial.resourceVersion
                    val state = initial.configMaps.associateBy { it.namespacedKey() }.toMutableMap()
                    trySend(Result.success(state.sortedConfigMaps()))

                    configMapApi.openConfigMapWatch(normalizedNamespace, resourceVersion).use { watchSession ->
                        reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)

                        for (event in watchSession) {
                            if (!isActive) break
                            resourceVersion = event.resourceVersion ?: resourceVersion

                            when (event.type?.uppercase()) {
                                "ADDED", "MODIFIED" -> {
                                    event.configMap?.let { state[it.namespacedKey()] = it }
                                    trySend(Result.success(state.sortedConfigMaps()))
                                }

                                "DELETED" -> {
                                    event.configMap?.let { state.remove(it.namespacedKey()) }
                                    trySend(Result.success(state.sortedConfigMaps()))
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

    override suspend fun get(
        name: String,
        namespace: String,
    ): Result<ConfigMap> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = namespace.requireNamespace()
            val normalizedName = name.requireName("ConfigMap name")
            configMapApiFactory.create(apiClientProvider).readConfigMap(
                namespace = normalizedNamespace,
                name = normalizedName,
            )
        }
    }

    override suspend fun update(configMap: ConfigMap): Result<ConfigMap> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = configMap.namespace.requireNamespace()
            val normalizedName = configMap.name.requireName("ConfigMap name")
            configMapApiFactory.create(apiClientProvider).replaceConfigMap(
                namespace = normalizedNamespace,
                name = normalizedName,
                data = configMap.data,
            )
        }
    }
}

fun interface ConfigMapApiFactory {
    fun create(apiClientProvider: () -> ApiClient): ConfigMapApi

    data object Default : ConfigMapApiFactory {
        override fun create(apiClientProvider: () -> ApiClient): ConfigMapApi = KubernetesConfigMapApi(apiClientProvider)
    }
}

interface ConfigMapApi {
    fun listConfigMapsSnapshot(namespace: String): ConfigMapListSnapshot
    fun openConfigMapWatch(namespace: String, resourceVersion: String?): ConfigMapWatchSession
    fun readConfigMap(namespace: String, name: String): ConfigMap
    fun replaceConfigMap(namespace: String, name: String, data: Map<String, String>): ConfigMap
}

data class ConfigMapListSnapshot(
    val configMaps: List<ConfigMap>,
    val resourceVersion: String?,
)

data class ConfigMapWatchEvent(
    val type: String?,
    val configMap: ConfigMap?,
    val resourceVersion: String?,
)

interface ConfigMapWatchSession : Iterable<ConfigMapWatchEvent>, AutoCloseable

private class KubernetesConfigMapApi(
    private val apiClientProvider: () -> ApiClient,
) : ConfigMapApi {

    override fun listConfigMapsSnapshot(namespace: String): ConfigMapListSnapshot {
        val api = CoreV1Api(apiClientProvider())
        val response = if (namespace.isAllNamespacesSelection()) {
            api.listConfigMapForAllNamespaces().execute()
        } else {
            api.listNamespacedConfigMap(namespace).execute()
        }
        val fallbackNamespace = if (namespace.isAllNamespacesSelection()) null else namespace

        return ConfigMapListSnapshot(
            configMaps = response.items.orEmpty().mapNotNull { it.toDomainConfigMap(fallbackNamespace) },
            resourceVersion = response.metadata?.resourceVersion,
        )
    }

    override fun openConfigMapWatch(namespace: String, resourceVersion: String?): ConfigMapWatchSession {
        val api = CoreV1Api(apiClientProvider())
        val call = if (namespace.isAllNamespacesSelection()) {
            val requestBuilder = api.listConfigMapForAllNamespaces()
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        } else {
            val requestBuilder = api.listNamespacedConfigMap(namespace)
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        }
        val watch: Watch<V1ConfigMap> = Watch.createWatch(
            apiClientProvider(),
            call,
            V1_CONFIG_MAP_WATCH_TYPE,
        )
        val fallbackNamespace = if (namespace.isAllNamespacesSelection()) null else namespace
        return KubernetesConfigMapWatchSession(watch = watch, namespace = fallbackNamespace)
    }

    override fun readConfigMap(namespace: String, name: String): ConfigMap {
        val configMap = CoreV1Api(apiClientProvider())
            .readNamespacedConfigMap(name, namespace)
            .execute()
        return configMap.toDomainConfigMap(namespace)
            ?: throw IllegalStateException("ConfigMap payload is missing required metadata")
    }

    override fun replaceConfigMap(namespace: String, name: String, data: Map<String, String>): ConfigMap {
        val existing = CoreV1Api(apiClientProvider())
            .readNamespacedConfigMap(name, namespace)
            .execute()
        existing.data = data.toMutableMap()
        val replaced = CoreV1Api(apiClientProvider())
            .replaceNamespacedConfigMap(name, namespace, existing)
            .execute()
        return replaced.toDomainConfigMap(namespace)
            ?: throw IllegalStateException("ConfigMap payload is missing required metadata")
    }
}

private class KubernetesConfigMapWatchSession(
    private val watch: Watch<V1ConfigMap>,
    private val namespace: String?,
) : ConfigMapWatchSession {
    override fun iterator(): Iterator<ConfigMapWatchEvent> {
        val delegate = watch.iterator()
        return object : Iterator<ConfigMapWatchEvent> {
            override fun hasNext(): Boolean = delegate.hasNext()

            override fun next(): ConfigMapWatchEvent {
                val event = delegate.next()
                val obj = event.`object`
                return ConfigMapWatchEvent(
                    type = event.type,
                    configMap = obj?.toDomainConfigMap(namespace),
                    resourceVersion = obj?.metadata?.resourceVersion,
                )
            }
        }
    }

    override fun close() {
        watch.close()
    }
}

private fun V1ConfigMap.toDomainConfigMap(fallbackNamespace: String? = null): ConfigMap? {
    val normalizedName = metadata?.name?.trim().orEmpty()
    if (normalizedName.isEmpty()) return null

    val normalizedNamespace = metadata?.namespace?.trim().orEmpty().ifEmpty {
        fallbackNamespace.orEmpty()
    }
    if (normalizedNamespace.isEmpty()) return null

    return ConfigMap(
        name = normalizedName,
        namespace = normalizedNamespace,
        data = data.orEmpty().toMap(),
        age = metadata?.creationTimestamp?.toInstant().toAgeString(),
    )
}

private fun Instant?.toAgeString(now: Instant = Instant.now()): String {
    val createdAt = this ?: return AGE_UNKNOWN
    if (createdAt.isAfter(now)) return "<1m"

    val duration = Duration.between(createdAt, now)
    return when {
        duration.toDays() > 0 -> "${duration.toDays()}d"
        duration.toHours() > 0 -> "${duration.toHours()}h"
        duration.toMinutes() > 0 -> "${duration.toMinutes()}m"
        else -> "<1m"
    }
}

private fun Map<String, ConfigMap>.sortedConfigMaps(): List<ConfigMap> = values.sortedBy { it.name }

private fun ConfigMap.namespacedKey(): String = "${namespace.trim()}/${name.trim()}"

private fun String.requireNamespace(): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "Namespace must not be empty" }
    return normalized
}

private fun String.requireName(field: String): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "$field must not be empty" }
    return normalized
}

private fun buildConfigMapApiClient(kubeConfigPath: Path): ApiClient {
    require(Files.exists(kubeConfigPath)) { "Kubeconfig file not found at $kubeConfigPath" }
    val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    val kubeConfig = KubeConfig.loadKubeConfig(StringReader(raw))
    return ClientBuilder.kubeconfig(kubeConfig)
        .build()
        .setLenientOnJson(true)
}

private const val WATCH_TIMEOUT_SECONDS = 30
private const val AGE_UNKNOWN = "unknown"

private val V1_CONFIG_MAP_WATCH_TYPE =
    object : com.google.gson.reflect.TypeToken<Watch.Response<V1ConfigMap>>() {}.type
