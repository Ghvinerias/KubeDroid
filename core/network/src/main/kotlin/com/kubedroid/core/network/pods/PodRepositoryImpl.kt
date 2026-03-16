package com.kubedroid.core.network.pods

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.kubedroid.core.database.cache.CacheRepository
import com.kubedroid.core.database.cache.CachedPod
import com.kubedroid.core.database.cache.StaleDataIndicator
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Watch
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
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
 * Kubernetes API-backed [PodRepository] implementation.
 */
class PodRepositoryImpl(
    private val apiClientProvider: () -> ApiClient,
    private val cacheRepository: CacheRepository? = null,
    private val contextNameProvider: () -> String = { DEFAULT_CONTEXT_NAME },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchReconnectInitialBackoffMillis: Long = 1_000L,
    private val watchReconnectMaxBackoffMillis: Long = 30_000L,
    private val podApiFactory: PodApiFactory = PodApiFactory.Default,
) : PodRepository {

    constructor(
        kubeConfigPath: Path,
        cacheRepository: CacheRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        watchReconnectInitialBackoffMillis: Long = 1_000L,
        watchReconnectMaxBackoffMillis: Long = 30_000L,
        podApiFactory: PodApiFactory = PodApiFactory.Default,
    ) : this(
        apiClientProvider = { buildApiClient(kubeConfigPath) },
        cacheRepository = cacheRepository,
        contextNameProvider = { readCurrentContextName(kubeConfigPath) },
        ioDispatcher = ioDispatcher,
        watchReconnectInitialBackoffMillis = watchReconnectInitialBackoffMillis,
        watchReconnectMaxBackoffMillis = watchReconnectMaxBackoffMillis,
        podApiFactory = podApiFactory,
    )

    override suspend fun listPods(namespace: String): Result<PodListResult> = withContext(ioDispatcher) {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isEmpty()) {
            return@withContext Result.failure(EmptyNamespaceException())
        }
        val contextName = contextNameProvider()
        val cachedPods = getCachedPods(
            cacheRepository = cacheRepository,
            contextName = contextName,
            namespace = normalizedNamespace,
        )
        val podApi = podApiFactory.create(apiClientProvider)
        val liveResult = runCatching {
            val livePods = podApi.listPods(normalizedNamespace).pods
            val fetchedAt = System.currentTimeMillis()
            cachePods(
                cacheRepository = cacheRepository,
                contextName = contextName,
                namespace = normalizedNamespace,
                pods = livePods,
                fetchedAt = fetchedAt,
            )
            PodListResult(
                pods = livePods,
                staleDataIndicator = StaleDataIndicator(
                    isFresh = true,
                    lastFetchedAt = fetchedAt,
                    source = StaleDataIndicator.Source.Live,
                ),
            )
        }

        liveResult.fold(
            onSuccess = { Result.success(it) },
            onFailure = { throwable ->
                val mapped = mapPodError(
                    throwable = throwable,
                    namespace = namespace,
                    podName = null,
                    operation = "list pods",
                )
                if (cachedPods != null) {
                    Result.success(cachedPods)
                } else {
                    Result.failure(mapped)
                }
            },
        )
    }

    override suspend fun deletePod(namespace: String, podName: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = namespace.requireNamespace()
            val normalizedPodName = podName.requirePodName()
            podApiFactory.create(apiClientProvider).deletePod(normalizedNamespace, normalizedPodName)
        }.mapFailure { throwable ->
            mapPodError(
                throwable = throwable,
                namespace = namespace,
                podName = podName,
                operation = "delete pod",
            )
        }
    }

    override fun watchPods(namespace: String): Flow<Result<PodListResult>> = callbackFlow {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isEmpty()) {
            trySend(Result.failure(EmptyNamespaceException()))
            close()
            return@callbackFlow
        }

        val watcherJob = launch(ioDispatcher) {
            var reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)
            var resourceVersion: String? = null
            var shouldReconnect = true
            val contextName = contextNameProvider()
            getCachedPods(
                cacheRepository = cacheRepository,
                contextName = contextName,
                namespace = normalizedNamespace,
            )
                ?.let { trySend(Result.success(it)) }

            while (isActive && shouldReconnect) {
                val podApi = podApiFactory.create(apiClientProvider)

                try {
                    val initial = podApi.listPods(normalizedNamespace)
                    resourceVersion = initial.resourceVersion
                    val state = initial.pods.associateBy { it.namespacedKey() }.toMutableMap()
                    cachePods(
                        cacheRepository = cacheRepository,
                        contextName = contextName,
                        namespace = normalizedNamespace,
                        pods = state.sortedPods(),
                        fetchedAt = System.currentTimeMillis(),
                    )
                    trySend(Result.success(state.sortedPods().toLivePodListResult()))

                    podApi.openPodWatch(normalizedNamespace, resourceVersion).use { watchSession ->
                        reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)

                        for (event in watchSession) {
                            if (!isActive) break
                            resourceVersion = event.resourceVersion ?: resourceVersion

                            when (event.type?.uppercase()) {
                                "ADDED", "MODIFIED" -> {
                                    event.pod?.let { state[it.namespacedKey()] = it }
                                    cachePods(
                                        cacheRepository = cacheRepository,
                                        contextName = contextName,
                                        namespace = normalizedNamespace,
                                        pods = state.sortedPods(),
                                        fetchedAt = System.currentTimeMillis(),
                                    )
                                    trySend(Result.success(state.sortedPods().toLivePodListResult()))
                                }
                                "DELETED" -> {
                                    event.pod?.let { state.remove(it.namespacedKey()) }
                                    cachePods(
                                        cacheRepository = cacheRepository,
                                        contextName = contextName,
                                        namespace = normalizedNamespace,
                                        pods = state.sortedPods(),
                                        fetchedAt = System.currentTimeMillis(),
                                    )
                                    trySend(Result.success(state.sortedPods().toLivePodListResult()))
                                }
                                "BOOKMARK" -> Unit
                                else -> Unit
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    val mapped = mapPodError(
                        throwable = throwable,
                        namespace = normalizedNamespace,
                        podName = null,
                        operation = "watch pods",
                    )
                    val cachedFallback = getCachedPods(
                        cacheRepository = cacheRepository,
                        contextName = contextName,
                        namespace = normalizedNamespace,
                    )
                    if (cachedFallback != null) {
                        trySend(Result.success(cachedFallback))
                    } else {
                        trySend(Result.failure(mapped))
                    }

                    if (mapped is ForbiddenNamespaceAccessException) {
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

        awaitClose {
            watcherJob.cancel()
        }
    }.flowOn(ioDispatcher)

    private fun mapPodError(
        throwable: Throwable,
        namespace: String,
        podName: String?,
        operation: String,
    ): Throwable {
        return when (throwable) {
            is EmptyNamespaceException -> throwable
            is EmptyPodNameException -> throwable
            is ApiException -> when (throwable.code) {
                403 -> ForbiddenNamespaceAccessException(namespace)
                404 -> if (podName != null) {
                    PodNotFoundException(namespace = namespace, podName = podName)
                } else {
                    KubernetesPodApiException("Pod API returned HTTP 404 while trying to $operation", throwable)
                }
                else -> KubernetesPodApiException(
                    message = "Pod API returned HTTP ${throwable.code} while trying to $operation",
                    cause = throwable,
                )
            }
            is PodWatchDisconnectedException -> throwable
            else -> {
                if (operation == "watch pods") {
                    PodWatchDisconnectedException(throwable.message, throwable)
                } else {
                    KubernetesPodApiException("Failed to $operation: ${throwable.message}", throwable)
                }
            }
        }
    }
}

fun interface PodApiFactory {
    fun create(apiClientProvider: () -> ApiClient): PodApi

    data object Default : PodApiFactory {
        override fun create(apiClientProvider: () -> ApiClient): PodApi = KubernetesPodApi(apiClientProvider)
    }
}

interface PodApi {
    fun listPods(namespace: String): PodListSnapshot
    fun deletePod(namespace: String, podName: String)
    fun openPodWatch(namespace: String, resourceVersion: String?): PodWatchSession
}

data class PodListSnapshot(
    val pods: List<Pod>,
    val resourceVersion: String?,
)

data class PodWatchEvent(
    val type: String?,
    val pod: Pod?,
    val resourceVersion: String?,
)

interface PodWatchSession : Iterable<PodWatchEvent>, AutoCloseable

private class KubernetesPodApi(
    private val apiClientProvider: () -> ApiClient,
) : PodApi {

    override fun listPods(namespace: String): PodListSnapshot {
        val apiClient = apiClientProvider()
        val api = CoreV1Api(apiClient)
        val call = if (namespace.isAllNamespacesSelection()) {
            api.listPodForAllNamespaces().buildCall(null)
        } else {
            api.listNamespacedPod(namespace).buildCall(null)
        }
        val response = apiClient.execute<JsonObject>(
            call,
            object : TypeToken<JsonObject>() {}.type,
        ).data

        val fallbackNamespace = if (namespace.isAllNamespacesSelection()) null else namespace
        return PodListSnapshot(
            pods = response
                ?.optArray("items")
                ?.mapNotNull { it.asJsonObjectOrNull()?.toDomainPod(fallbackNamespace) }
                .orEmpty(),
            resourceVersion = response
                ?.optObject("metadata")
                ?.optString("resourceVersion"),
        )
    }

    override fun deletePod(namespace: String, podName: String) {
        coreV1Api()
            .deleteNamespacedPod(podName, namespace)
            .execute()
    }

    override fun openPodWatch(namespace: String, resourceVersion: String?): PodWatchSession {
        val api = coreV1Api()
        val call = if (namespace.isAllNamespacesSelection()) {
            val requestBuilder = api.listPodForAllNamespaces()
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        } else {
            val requestBuilder = api.listNamespacedPod(namespace)
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        }
        val watch: Watch<JsonObject> = Watch.createWatch<JsonObject>(
            apiClientProvider(),
            call,
            object : TypeToken<Watch.Response<JsonObject>>() {}.type,
        )
        return KubernetesPodWatchSession(watch)
    }

    private fun coreV1Api(): CoreV1Api = CoreV1Api(apiClientProvider())
}

private class KubernetesPodWatchSession(
    private val watch: Watch<JsonObject>,
) : PodWatchSession {
    override fun iterator(): Iterator<PodWatchEvent> {
        val delegate = watch.iterator()
        return object : Iterator<PodWatchEvent> {
            override fun hasNext(): Boolean = delegate.hasNext()

            override fun next(): PodWatchEvent {
                val event = delegate.next()
                val pod = event.`object`
                return PodWatchEvent(
                    type = event.type,
                    pod = pod?.toDomainPod(),
                    resourceVersion = pod?.optObject("metadata")?.optString("resourceVersion"),
                )
            }
        }
    }

    override fun close() {
        watch.close()
    }
}

private fun JsonObject.toDomainPod(fallbackNamespace: String? = null): Pod {
    val metadata = optObject("metadata")
    val podStatus = optObject("status")
    val spec = optObject("spec")

    val containers = podStatus
        ?.optArray("containerStatuses")
        ?.mapNotNull { it.asJsonObjectOrNull()?.toDomainContainer() }
        .orEmpty()

    val startTimeEpochMillis = podStatus
        ?.optString("startTime")
        ?.let(::parseIsoInstantOrNull)

    return Pod(
        name = metadata?.optString("name").orEmpty(),
        namespace = metadata?.optString("namespace") ?: fallbackNamespace.orEmpty(),
        status = podStatus.toDomainStatus(),
        nodeName = spec?.optString("nodeName"),
        startTimeEpochMillis = startTimeEpochMillis,
        containers = containers,
    )
}

private fun JsonObject.toDomainContainer(): ContainerInfo {
    return ContainerInfo(
        name = optString("name").orEmpty(),
        image = optString("image").orEmpty(),
        ready = optBoolean("ready"),
        restartCount = optInt("restartCount"),
    )
}

private fun JsonObject?.toDomainStatus(): PodStatus {
    return when (this?.optString("phase")?.uppercase()) {
        "PENDING" -> PodStatus.Pending
        "RUNNING" -> PodStatus.Running
        "SUCCEEDED" -> PodStatus.Succeeded
        "FAILED" -> PodStatus.Failed
        else -> PodStatus.Unknown
    }
}

private fun String.requireNamespace(): String {
    if (isBlank()) throw EmptyNamespaceException()
    return trim()
}

private fun String.requirePodName(): String {
    if (isBlank()) throw EmptyPodNameException()
    return trim()
}

private fun Map<String, Pod>.sortedPods(): List<Pod> = values.sortedBy { it.name }

private fun Pod.namespacedKey(): String = "${namespace.trim()}/${name.trim()}"

private suspend fun getCachedPods(
    cacheRepository: CacheRepository?,
    contextName: String,
    namespace: String,
): PodListResult? {
    val repository = cacheRepository ?: return null
    val cachedNamespace = if (namespace.isAllNamespacesSelection()) null else namespace
    val cachedResources = repository.getCachedResources(
        contextName = contextName,
        namespace = cachedNamespace,
    ).getOrNull() ?: return null

    val indicator = podStaleIndicator(cachedResources.pods)
    return if (cachedResources.pods.isEmpty()) {
        null
    } else {
        PodListResult(
            pods = cachedResources.pods.map { it.toDomainPod() }.sortedBy { it.name },
            staleDataIndicator = indicator,
        )
    }
}

private suspend fun cachePods(
    cacheRepository: CacheRepository?,
    contextName: String,
    namespace: String,
    pods: List<Pod>,
    fetchedAt: Long,
) {
    val repository = cacheRepository ?: return
    repository.cacheResources(
        contextName = contextName,
        pods = pods.map { pod ->
            CachedPod(
                contextName = contextName,
                namespace = pod.namespace,
                name = pod.name,
                status = pod.status.cacheValue(),
                lastFetchedAt = fetchedAt,
            )
        },
        fetchedAt = fetchedAt,
    )
}

private fun podStaleIndicator(cachedPods: List<CachedPod>): StaleDataIndicator {
    val lastFetchedAt = cachedPods.maxOfOrNull { it.lastFetchedAt }
    val isFresh = lastFetchedAt != null && (System.currentTimeMillis() - lastFetchedAt) <= POD_CACHE_TTL_MILLIS
    return StaleDataIndicator(
        isFresh = isFresh,
        lastFetchedAt = lastFetchedAt,
        source = StaleDataIndicator.Source.Cache,
    )
}

private fun CachedPod.toDomainPod(): Pod = Pod(
    name = name,
    namespace = namespace,
    status = status.toPodStatus(),
    nodeName = null,
    startTimeEpochMillis = null,
    containers = emptyList(),
)

private fun String.toPodStatus(): PodStatus = when (uppercase()) {
    "PENDING" -> PodStatus.Pending
    "RUNNING" -> PodStatus.Running
    "SUCCEEDED" -> PodStatus.Succeeded
    "FAILED" -> PodStatus.Failed
    else -> PodStatus.Unknown
}

private fun PodStatus.cacheValue(): String = when (this) {
    PodStatus.Pending -> "PENDING"
    PodStatus.Running -> "RUNNING"
    PodStatus.Succeeded -> "SUCCEEDED"
    PodStatus.Failed -> "FAILED"
    PodStatus.Unknown -> "UNKNOWN"
}

private fun List<Pod>.toLivePodListResult(
    fetchedAt: Long = System.currentTimeMillis(),
): PodListResult = PodListResult(
    pods = this,
    staleDataIndicator = StaleDataIndicator(
        isFresh = true,
        lastFetchedAt = fetchedAt,
        source = StaleDataIndicator.Source.Live,
    ),
)

private fun buildApiClient(kubeConfigPath: Path): ApiClient {
    if (!Files.exists(kubeConfigPath)) {
        throw KubernetesPodApiException("Kubeconfig file not found at $kubeConfigPath")
    }
    val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    val kubeConfig = try {
        KubeConfig.loadKubeConfig(StringReader(raw))
    } catch (throwable: Throwable) {
        throw KubernetesPodApiException("Invalid kubeconfig content", throwable)
    }
    return ClientBuilder.kubeconfig(kubeConfig)
        .build()
        // Newer Kubernetes APIs may include fields unknown to this client model version.
        .setLenientOnJson(true)
}

private fun readCurrentContextName(kubeConfigPath: Path): String {
    if (!Files.exists(kubeConfigPath)) return DEFAULT_CONTEXT_NAME
    val raw = runCatching { Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8) }
        .getOrNull()
        ?: return DEFAULT_CONTEXT_NAME
    return runCatching { KubeConfig.loadKubeConfig(StringReader(raw)).currentContext }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_CONTEXT_NAME
}

private fun JsonObject.optArray(name: String): List<JsonElement>? {
    return if (has(name) && get(name).isJsonArray) getAsJsonArray(name).toList() else null
}

private fun JsonObject.optObject(name: String): JsonObject? {
    return if (has(name) && get(name).isJsonObject) getAsJsonObject(name) else null
}

private fun JsonObject.optString(name: String): String? {
    if (!has(name)) return null
    val element = get(name)
    if (element == null || element.isJsonNull || !element.isJsonPrimitive) return null
    return runCatching { element.asString }.getOrNull()
}

private fun JsonObject.optBoolean(name: String): Boolean {
    if (!has(name)) return false
    val element = get(name)
    if (element == null || element.isJsonNull || !element.isJsonPrimitive) return false
    return runCatching { element.asBoolean }.getOrDefault(false)
}

private fun JsonObject.optInt(name: String): Int {
    if (!has(name)) return 0
    val element = get(name)
    if (element == null || element.isJsonNull || !element.isJsonPrimitive) return 0
    return runCatching { element.asInt }.getOrDefault(0)
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

private fun parseIsoInstantOrNull(value: String): Long? {
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

private fun <T> Result<T>.mapFailure(mapper: (Throwable) -> Throwable): Result<T> {
    return fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapper(it)) },
    )
}

class EmptyNamespaceException : IllegalArgumentException("Namespace must not be empty")

class EmptyPodNameException : IllegalArgumentException("Pod name must not be empty")

class ForbiddenNamespaceAccessException(namespace: String) :
    Exception("Forbidden access to namespace '$namespace'")

class PodNotFoundException(namespace: String, podName: String) :
    Exception("Pod '$podName' was not found in namespace '$namespace'")

class PodWatchDisconnectedException(
    message: String?,
    cause: Throwable? = null,
) : Exception(message ?: "Pod watch disconnected", cause)

class KubernetesPodApiException(
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause)

private const val WATCH_TIMEOUT_SECONDS = 30
private const val POD_CACHE_TTL_MILLIS = 30_000L
private const val DEFAULT_CONTEXT_NAME = "default"
