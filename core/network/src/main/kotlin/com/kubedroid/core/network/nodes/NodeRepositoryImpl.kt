package com.kubedroid.core.network.nodes

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.kubedroid.core.database.cache.CacheRepository
import com.kubedroid.core.database.cache.CachedNode
import com.kubedroid.core.database.cache.StaleDataIndicator
import io.kubernetes.client.Metrics
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Eviction
import io.kubernetes.client.openapi.models.V1Node
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.PatchUtils
import io.kubernetes.client.util.Watch
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kubernetes API-backed [NodeRepository] implementation.
 */
class NodeRepositoryImpl(
    private val apiClientProvider: () -> ApiClient,
    private val cacheRepository: CacheRepository? = null,
    private val contextNameProvider: () -> String = { DEFAULT_CONTEXT_NAME },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchReconnectInitialBackoffMillis: Long = 1_000L,
    private val watchReconnectMaxBackoffMillis: Long = 30_000L,
    private val podEvictionRetryBaseDelayMillis: Long = 2_000L,
    private val podEvictionRetryMaxDelayMillis: Long = 15_000L,
    private val maxPodEvictionWaitMillis: Long = 120_000L,
    private val podDeletionPollIntervalMillis: Long = 1_000L,
    private val maxPodDeletionPolls: Int = 60,
    private val nodeApiFactory: NodeApiFactory = NodeApiFactory.Default,
) : NodeRepository {

    constructor(
        kubeConfigPath: Path,
        cacheRepository: CacheRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        watchReconnectInitialBackoffMillis: Long = 1_000L,
        watchReconnectMaxBackoffMillis: Long = 30_000L,
        podEvictionRetryBaseDelayMillis: Long = 2_000L,
        podEvictionRetryMaxDelayMillis: Long = 15_000L,
        maxPodEvictionWaitMillis: Long = 120_000L,
        podDeletionPollIntervalMillis: Long = 1_000L,
        maxPodDeletionPolls: Int = 60,
        nodeApiFactory: NodeApiFactory = NodeApiFactory.Default,
    ) : this(
        apiClientProvider = { buildNodeApiClient(kubeConfigPath) },
        cacheRepository = cacheRepository,
        contextNameProvider = { readCurrentContextName(kubeConfigPath) },
        ioDispatcher = ioDispatcher,
        watchReconnectInitialBackoffMillis = watchReconnectInitialBackoffMillis,
        watchReconnectMaxBackoffMillis = watchReconnectMaxBackoffMillis,
        podEvictionRetryBaseDelayMillis = podEvictionRetryBaseDelayMillis,
        podEvictionRetryMaxDelayMillis = podEvictionRetryMaxDelayMillis,
        maxPodEvictionWaitMillis = maxPodEvictionWaitMillis,
        podDeletionPollIntervalMillis = podDeletionPollIntervalMillis,
        maxPodDeletionPolls = maxPodDeletionPolls,
        nodeApiFactory = nodeApiFactory,
    )

    override suspend fun list(): Result<NodeListResult> = withContext(ioDispatcher) {
        val contextName = contextNameProvider()
        val cachedNodes = getCachedNodes(
            cacheRepository = cacheRepository,
            contextName = contextName,
        )
        runCatching {
            val nodeApi = nodeApiFactory.create(apiClientProvider)
            val snapshot = nodeApi.listNodes()
            val metricsByNode = nodeApi.listNodeMetricsOrNull()
            val liveNodes = snapshot.nodes
                .map { record -> record.toDomainNode(metricsByNode[record.name]) }
                .sortedBy { it.name }
            val fetchedAt = System.currentTimeMillis()
            cacheNodes(
                cacheRepository = cacheRepository,
                contextName = contextName,
                nodes = liveNodes,
                fetchedAt = fetchedAt,
            )
            liveNodes.toLiveNodeListResult(fetchedAt)
        }.recoverCatching { throwable ->
            cachedNodes ?: throw throwable
        }
    }

    override fun watch(): Flow<Result<NodeListResult>> = callbackFlow {
        val watcherJob = launch(ioDispatcher) {
            val nodeApi = nodeApiFactory.create(apiClientProvider)
            var reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)
            var resourceVersion: String? = null
            var shouldReconnect = true
            val contextName = contextNameProvider()
            getCachedNodes(
                cacheRepository = cacheRepository,
                contextName = contextName,
            )?.let { trySend(Result.success(it)) }

            while (isActive && shouldReconnect) {
                try {
                    val initial = nodeApi.listNodes()
                    resourceVersion = initial.resourceVersion
                    val state = initial.nodes.associateBy { it.name }.toMutableMap()
                    val fetchedAt = System.currentTimeMillis()
                    val liveNodes = state.toDomainNodes(metricsByNode = nodeApi.listNodeMetricsOrNull())
                    cacheNodes(
                        cacheRepository = cacheRepository,
                        contextName = contextName,
                        nodes = liveNodes,
                        fetchedAt = fetchedAt,
                    )
                    trySend(Result.success(liveNodes.toLiveNodeListResult(fetchedAt)))

                    nodeApi.openNodeWatch(resourceVersion).use { watchSession ->
                        reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)

                        for (event in watchSession) {
                            if (!isActive) break
                            resourceVersion = event.resourceVersion ?: resourceVersion

                            when (event.type?.uppercase()) {
                                "ADDED", "MODIFIED" -> {
                                    event.node?.let { state[it.name] = it }
                                    val fetchedAt = System.currentTimeMillis()
                                    val liveNodes = state.toDomainNodes(metricsByNode = nodeApi.listNodeMetricsOrNull())
                                    cacheNodes(
                                        cacheRepository = cacheRepository,
                                        contextName = contextName,
                                        nodes = liveNodes,
                                        fetchedAt = fetchedAt,
                                    )
                                    trySend(Result.success(liveNodes.toLiveNodeListResult(fetchedAt)))
                                }

                                "DELETED" -> {
                                    event.node?.name?.let { state.remove(it) }
                                    val fetchedAt = System.currentTimeMillis()
                                    val liveNodes = state.toDomainNodes(metricsByNode = nodeApi.listNodeMetricsOrNull())
                                    cacheNodes(
                                        cacheRepository = cacheRepository,
                                        contextName = contextName,
                                        nodes = liveNodes,
                                        fetchedAt = fetchedAt,
                                    )
                                    trySend(Result.success(liveNodes.toLiveNodeListResult(fetchedAt)))
                                }

                                "BOOKMARK" -> Unit
                                else -> Unit
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    val cachedFallback = getCachedNodes(
                        cacheRepository = cacheRepository,
                        contextName = contextName,
                    )
                    if (cachedFallback != null) {
                        trySend(Result.success(cachedFallback))
                    } else {
                        trySend(Result.failure(throwable))
                    }
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

    override suspend fun cordon(nodeName: String): NodeActionResult = patchUnschedulable(nodeName, unschedulable = true)

    override suspend fun uncordon(nodeName: String): NodeActionResult = patchUnschedulable(nodeName, unschedulable = false)

    override suspend fun drain(nodeName: String): NodeActionResult {
        val terminal = drainWithProgress(nodeName)
            .first { it is NodeDrainProgress.Completed } as NodeDrainProgress.Completed
        return terminal.result
    }

    override fun drainWithProgress(nodeName: String): Flow<NodeDrainProgress> = flow {
        val normalizedNodeName = nodeName.trim()
        if (normalizedNodeName.isEmpty()) {
            emit(NodeDrainProgress.Completed(NodeActionResult.Failure(IllegalArgumentException("Node name is required"))))
            return@flow
        }

        val nodeApi = nodeApiFactory.create(apiClientProvider)
        val podsOnNode = runCatching {
            nodeApi.listPodsOnNode(normalizedNodeName)
                .filter { it.isDrainable }
                .sortedBy { "${it.namespace}/${it.name}" }
        }.getOrElse { throwable ->
            emit(NodeDrainProgress.Completed(mapDrainTerminalError(throwable)))
            return@flow
        }

        emit(
            NodeDrainProgress.Started(
                nodeName = normalizedNodeName,
                totalPods = podsOnNode.size,
            ),
        )

        if (podsOnNode.isEmpty()) {
            emit(
                NodeDrainProgress.Completed(
                    NodeActionResult.Success(normalizedNodeName, NodeActionResult.Action.DRAIN),
                ),
            )
            return@flow
        }

        var evictedPods = 0
        for (pod in podsOnNode) {
            var attempt = 0
            var waitedOnPdbMillis = 0L
            while (true) {
                attempt += 1
                emit(
                    NodeDrainProgress.EvictionAttempt(
                        nodeName = normalizedNodeName,
                        namespace = pod.namespace,
                        podName = pod.name,
                        attempt = attempt,
                    ),
                )

                val evictionAttempt = runCatching {
                    nodeApi.evictPod(namespace = pod.namespace, podName = pod.name)
                }
                if (evictionAttempt.isSuccess) {
                    break
                }

                val error = evictionAttempt.exceptionOrNull() ?: IllegalStateException("Eviction failed")
                val apiError = error as? ApiException
                when (apiError?.code) {
                    404 -> break
                    403 -> {
                        emit(NodeDrainProgress.Completed(NodeActionResult.Forbidden))
                        return@flow
                    }

                    429 -> {
                        if (waitedOnPdbMillis >= maxPodEvictionWaitMillis) {
                            emit(NodeDrainProgress.Completed(NodeActionResult.Conflict))
                            return@flow
                        }

                        val retryDelayMillis = apiError.retryAfterMillis(
                            defaultDelayMillis = podEvictionRetryBaseDelayMillis,
                            maxDelayMillis = podEvictionRetryMaxDelayMillis,
                        )
                        emit(
                            NodeDrainProgress.WaitingOnDisruptionBudget(
                                nodeName = normalizedNodeName,
                                namespace = pod.namespace,
                                podName = pod.name,
                                attempt = attempt,
                                retryDelayMillis = retryDelayMillis,
                            ),
                        )
                        waitedOnPdbMillis += retryDelayMillis
                        delay(retryDelayMillis)
                    }

                    else -> {
                        emit(NodeDrainProgress.Completed(NodeActionResult.Failure(error)))
                        return@flow
                    }
                }
            }

            val deletionResult = waitForPodDeletion(nodeApi = nodeApi, pod = pod)
            if (deletionResult != null) {
                emit(NodeDrainProgress.Completed(deletionResult))
                return@flow
            }

            evictedPods += 1
            emit(
                NodeDrainProgress.PodEvicted(
                    nodeName = normalizedNodeName,
                    namespace = pod.namespace,
                    podName = pod.name,
                    evictedPods = evictedPods,
                    totalPods = podsOnNode.size,
                ),
            )
        }

        emit(
            NodeDrainProgress.Completed(
                NodeActionResult.Success(normalizedNodeName, NodeActionResult.Action.DRAIN),
            ),
        )
    }.flowOn(ioDispatcher)

    private suspend fun patchUnschedulable(
        nodeName: String,
        unschedulable: Boolean,
    ): NodeActionResult = withContext(ioDispatcher) {
        val normalizedNodeName = nodeName.trim()
        if (normalizedNodeName.isEmpty()) {
            return@withContext NodeActionResult.Failure(IllegalArgumentException("Node name is required"))
        }

        runCatching {
            nodeApiFactory.create(apiClientProvider)
                .patchNodeUnschedulable(normalizedNodeName, unschedulable)
            NodeActionResult.Success(
                nodeName = normalizedNodeName,
                action = if (unschedulable) NodeActionResult.Action.CORDON else NodeActionResult.Action.UNCORDON,
            )
        }.getOrElse(::mapActionError)
    }

    private suspend fun waitForPodDeletion(
        nodeApi: NodeApi,
        pod: DrainTargetPod,
    ): NodeActionResult? {
        repeat(maxPodDeletionPolls.coerceAtLeast(1)) { index ->
            val status = runCatching {
                nodeApi.isPodDeleted(namespace = pod.namespace, podName = pod.name)
            }
            val error = status.exceptionOrNull()
            if (error == null && status.getOrThrow()) {
                return null
            }
            val apiError = error as? ApiException
            when (apiError?.code) {
                404 -> return null
                403 -> return NodeActionResult.Forbidden
                else -> {
                    if (error != null) {
                        return NodeActionResult.Failure(error)
                    }
                }
            }

            if (index < maxPodDeletionPolls - 1) {
                delay(podDeletionPollIntervalMillis.coerceAtLeast(1L))
            }
        }
        return NodeActionResult.Conflict
    }

    private fun mapActionError(throwable: Throwable): NodeActionResult = when ((throwable as? ApiException)?.code) {
        403 -> NodeActionResult.Forbidden
        404 -> NodeActionResult.NotFound
        409 -> NodeActionResult.Conflict
        else -> NodeActionResult.Failure(throwable)
    }

    private fun mapDrainTerminalError(throwable: Throwable): NodeActionResult = when ((throwable as? ApiException)?.code) {
        403 -> NodeActionResult.Forbidden
        404 -> NodeActionResult.NotFound
        409, 429 -> NodeActionResult.Conflict
        else -> NodeActionResult.Failure(throwable)
    }
}

fun interface NodeApiFactory {
    fun create(apiClientProvider: () -> ApiClient): NodeApi

    data object Default : NodeApiFactory {
        override fun create(apiClientProvider: () -> ApiClient): NodeApi = KubernetesNodeApi(apiClientProvider)
    }
}

interface NodeApi {
    fun listNodes(): NodeListSnapshot
    fun openNodeWatch(resourceVersion: String?): NodeWatchSession
    fun patchNodeUnschedulable(nodeName: String, unschedulable: Boolean)
    fun listPodsOnNode(nodeName: String): List<DrainTargetPod>
    fun evictPod(namespace: String, podName: String)
    fun isPodDeleted(namespace: String, podName: String): Boolean
    fun listNodeMetricsOrNull(): Map<String, NodeRawMetrics>
}

data class NodeListSnapshot(
    val nodes: List<NodeRecord>,
    val resourceVersion: String?,
)

data class NodeWatchEvent(
    val type: String?,
    val node: NodeRecord?,
    val resourceVersion: String?,
)

interface NodeWatchSession : Iterable<NodeWatchEvent>, AutoCloseable

data class NodeRecord(
    val name: String,
    val roles: List<String>,
    val kubeletVersion: String?,
    val internalIp: String?,
    val externalIp: String?,
    val ready: Boolean,
    val unschedulable: Boolean,
    val conditions: List<NodeCondition>,
    val allocatableCpuMillicores: Long?,
    val allocatableMemoryBytes: Long?,
)

data class NodeRawMetrics(
    val cpuMillicores: Long?,
    val memoryBytes: Long?,
)

data class DrainTargetPod(
    val namespace: String,
    val name: String,
    val isMirrorPod: Boolean,
    val isDaemonSetManaged: Boolean,
    val phase: String?,
) {
    val isDrainable: Boolean
        get() {
            val normalizedPhase = phase?.uppercase()
            val terminal = normalizedPhase == "SUCCEEDED" || normalizedPhase == "FAILED"
            return !isMirrorPod && !isDaemonSetManaged && !terminal
        }
}

private class KubernetesNodeApi(
    private val apiClientProvider: () -> ApiClient,
) : NodeApi {

    override fun listNodes(): NodeListSnapshot {
        val nodeList = coreV1Api().listNode().execute()
        return NodeListSnapshot(
            nodes = nodeList.items.orEmpty().map { it.toNodeRecord() },
            resourceVersion = nodeList.metadata?.resourceVersion,
        )
    }

    override fun openNodeWatch(resourceVersion: String?): NodeWatchSession {
        val api = coreV1Api()
        val requestBuilder = api.listNode()
            .watch(true)
            .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
        if (!resourceVersion.isNullOrBlank()) {
            requestBuilder.resourceVersion(resourceVersion)
        }

        val watch: Watch<V1Node> = Watch.createWatch(
            apiClientProvider(),
            requestBuilder.buildCall(null),
            watchType,
        )
        return KubernetesNodeWatchSession(watch)
    }

    override fun patchNodeUnschedulable(nodeName: String, unschedulable: Boolean) {
        val apiClient = apiClientProvider()
        val patchBody = """{"spec":{"unschedulable":$unschedulable}}"""
        PatchUtils.patch(
            V1Node::class.java,
            {
                coreV1Api()
                    .patchNode(nodeName, V1Patch(patchBody))
                    .buildCall(null)
            },
            V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
            apiClient,
        )
    }

    override fun listPodsOnNode(nodeName: String): List<DrainTargetPod> {
        val api = coreV1Api()
        val call = api.listPodForAllNamespaces()
            .fieldSelector("spec.nodeName=$nodeName")
            .buildCall(null)
        val response = apiClientProvider().execute<JsonObject>(
            call,
            object : TypeToken<JsonObject>() {}.type,
        ).data
        return response
            ?.optArray("items")
            ?.mapNotNull { it.asJsonObjectOrNull()?.toDrainTargetPod() }
            .orEmpty()
    }

    override fun evictPod(namespace: String, podName: String) {
        val eviction = V1Eviction().apply {
            apiVersion = "policy/v1"
            kind = "Eviction"
            metadata = V1ObjectMeta().apply {
                name = podName
                this.namespace = namespace
            }
        }

        coreV1Api()
            .createNamespacedPodEviction(podName, namespace, eviction)
            .execute()
    }

    override fun isPodDeleted(namespace: String, podName: String): Boolean {
        return try {
            val call = coreV1Api().readNamespacedPod(podName, namespace).buildCall(null)
            apiClientProvider().execute<JsonObject>(
                call,
                object : TypeToken<JsonObject>() {}.type,
            )
            false
        } catch (apiException: ApiException) {
            if (apiException.code == 404) true else throw apiException
        }
    }

    override fun listNodeMetricsOrNull(): Map<String, NodeRawMetrics> {
        return runCatching {
            val metrics = Metrics(apiClientProvider()).getNodeMetrics().items.orEmpty()
            metrics.associateBy(
                keySelector = { it.metadata?.name.orEmpty() },
                valueTransform = {
                    NodeRawMetrics(
                        cpuMillicores = it.usageQuantity("cpu").toCpuMillicores(),
                        memoryBytes = it.usageQuantity("memory").toMemoryBytes(),
                    )
                },
            ).filterKeys { it.isNotBlank() }
        }.getOrElse { throwable ->
            when ((throwable as? ApiException)?.code) {
                403, 404, 503 -> emptyMap()
                else -> emptyMap()
            }
        }
    }

    private fun coreV1Api(): CoreV1Api = CoreV1Api(apiClientProvider())

    private companion object {
        val watchType = object : TypeToken<Watch.Response<V1Node>>() {}.type
    }
}

private class KubernetesNodeWatchSession(
    private val watch: Watch<V1Node>,
) : NodeWatchSession {
    override fun iterator(): Iterator<NodeWatchEvent> {
        val delegate = watch.iterator()
        return object : Iterator<NodeWatchEvent> {
            override fun hasNext(): Boolean = delegate.hasNext()

            override fun next(): NodeWatchEvent {
                val event = delegate.next()
                val node = event.`object`
                return NodeWatchEvent(
                    type = event.type,
                    node = node?.toNodeRecord(),
                    resourceVersion = node?.metadata?.resourceVersion,
                )
            }
        }
    }

    override fun close() {
        watch.close()
    }
}

private fun Map<String, NodeRecord>.toDomainNodes(
    metricsByNode: Map<String, NodeRawMetrics>,
): List<Node> = values
    .map { it.toDomainNode(metricsByNode[it.name]) }
    .sortedBy { it.name }

private fun NodeRecord.toDomainNode(metrics: NodeRawMetrics?): Node {
    val cpuPercent = metrics
        ?.cpuMillicores
        ?.toPercentOf(allocatableCpuMillicores)
    val memoryPercent = metrics
        ?.memoryBytes
        ?.toPercentOf(allocatableMemoryBytes)

    return Node(
        name = name,
        roles = roles,
        kubeletVersion = kubeletVersion,
        internalIp = internalIp,
        externalIp = externalIp,
        ready = ready,
        unschedulable = unschedulable,
        conditions = conditions,
        metrics = if (metrics == null) {
            null
        } else {
            NodeMetrics(
                cpuUsageMillicores = metrics.cpuMillicores,
                memoryUsageBytes = metrics.memoryBytes,
                cpuUsagePercent = cpuPercent,
                memoryUsagePercent = memoryPercent,
            )
        },
    )
}

private fun V1Node.toNodeRecord(): NodeRecord {
    val metadata = metadata
    val status = status
    val conditions = status?.conditions.orEmpty()

    val roles = metadata
        ?.labels
        .orEmpty()
        .entries
        .asSequence()
        .mapNotNull { entry ->
            if (!entry.key.startsWith("node-role.kubernetes.io/")) {
                return@mapNotNull null
            }
            val roleFromKey = entry.key.removePrefix("node-role.kubernetes.io/")
            when {
                roleFromKey.isNotBlank() -> roleFromKey
                entry.value.isNotBlank() -> entry.value
                else -> null
            }
        }
        .distinct()
        .toList()

    return NodeRecord(
        name = metadata?.name.orEmpty(),
        roles = if (roles.isEmpty()) listOf("worker") else roles,
        kubeletVersion = status?.nodeInfo?.kubeletVersion,
        internalIp = status
            ?.addresses
            .orEmpty()
            .firstOrNull { it.type.equals("InternalIP", ignoreCase = true) }
            ?.address,
        externalIp = status
            ?.addresses
            .orEmpty()
            .firstOrNull { it.type.equals("ExternalIP", ignoreCase = true) }
            ?.address,
        ready = conditions.firstOrNull { it.type.equals("Ready", ignoreCase = true) }?.status == "True",
        unschedulable = spec?.unschedulable == true,
        conditions = conditions.map {
            NodeCondition(
                type = it.type.orEmpty(),
                status = it.status.orEmpty(),
                reason = it.reason,
                message = it.message,
                lastTransitionTimeEpochMillis = it.lastTransitionTime?.let { ts ->
                    runCatching { Instant.parse(ts.toString()).toEpochMilli() }.getOrNull()
                },
            )
        },
        allocatableCpuMillicores = status
            ?.allocatable
            .orEmpty()["cpu"]
            .toCpuMillicores(),
        allocatableMemoryBytes = status
            ?.allocatable
            .orEmpty()["memory"]
            .toMemoryBytes(),
    )
}

private fun JsonObject.toDrainTargetPod(): DrainTargetPod {
    val metadata = optObject("metadata")
    val ownerRefs = metadata?.optArray("ownerReferences").orEmpty()
    val daemonSetManaged = ownerRefs.any { owner ->
        owner.asJsonObjectOrNull()
            ?.optString("kind")
            ?.equals("DaemonSet", ignoreCase = true) == true
    }
    val annotations = metadata?.optObject("annotations")
    val isMirrorPod = annotations?.has("kubernetes.io/config.mirror") == true

    return DrainTargetPod(
        namespace = metadata?.optString("namespace").orEmpty(),
        name = metadata?.optString("name").orEmpty(),
        isMirrorPod = isMirrorPod,
        isDaemonSetManaged = daemonSetManaged,
        phase = optObject("status")?.optString("phase"),
    )
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

private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

private fun io.kubernetes.client.custom.NodeMetrics.usageQuantity(resource: String): Quantity? = usage?.get(resource)

private fun Quantity?.toCpuMillicores(): Long? {
    val raw = this?.toSuffixedString()?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val parsed = parseNumberAndSuffix(raw) ?: return null
    val cpuFactor = parsed.cpuFactor ?: return null
    val baseCores = parsed.number.multiply(cpuFactor)
    val milliCores = baseCores.multiply(BigDecimal(1_000))
    return milliCores.setScale(0, RoundingMode.HALF_UP).longValueExactOrNull()
}

private fun Quantity?.toMemoryBytes(): Long? {
    val raw = this?.toSuffixedString()?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val parsed = parseNumberAndSuffix(raw) ?: return null
    val memoryFactor = parsed.memoryFactor ?: return null
    val bytes = parsed.number.multiply(memoryFactor)
    return bytes.setScale(0, RoundingMode.HALF_UP).longValueExactOrNull()
}

private data class QuantityParse(
    val number: BigDecimal,
    val suffix: String,
) {
    val cpuFactor: BigDecimal?
        get() {
            return CPU_SUFFIX_FACTORS[suffix]
        }

    val memoryFactor: BigDecimal?
        get() {
            return MEMORY_SUFFIX_FACTORS[suffix]
        }
}

private fun parseNumberAndSuffix(value: String): QuantityParse? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null

    val match = QUANTITY_PATTERN.matchEntire(trimmed) ?: return null
    val numericPart = match.groupValues[1]
    val suffix = match.groupValues[2]

    val number = runCatching { BigDecimal(numericPart) }.getOrNull() ?: return null
    return QuantityParse(number = number, suffix = suffix)
}

private fun Long.toPercentOf(total: Long?): Float? {
    val denominator = total ?: return null
    if (denominator <= 0L) return null
    return (toDouble() / denominator.toDouble() * 100.0).toFloat()
}

private fun BigDecimal.longValueExactOrNull(): Long? = runCatching { longValueExact() }.getOrNull()

private fun ApiException.retryAfterMillis(
    defaultDelayMillis: Long,
    maxDelayMillis: Long,
): Long {
    val headerDelay = responseHeaders
        ?.entries
        ?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.trim()
        ?.toLongOrNull()
        ?.let { TimeUnit.SECONDS.toMillis(it) }

    return (headerDelay ?: defaultDelayMillis.coerceAtLeast(1L))
        .coerceAtMost(maxDelayMillis.coerceAtLeast(1L))
}

private suspend fun getCachedNodes(
    cacheRepository: CacheRepository?,
    contextName: String,
): NodeListResult? {
    val repository = cacheRepository ?: return null
    val cachedResources = repository.getCachedResources(
        contextName = contextName,
    ).getOrNull() ?: return null
    if (cachedResources.nodes.isEmpty()) return null

    return NodeListResult(
        nodes = cachedResources.nodes
            .map { it.toDomainNode() }
            .sortedBy { it.name },
        staleDataIndicator = nodeStaleIndicator(cachedResources.nodes),
    )
}

private suspend fun cacheNodes(
    cacheRepository: CacheRepository?,
    contextName: String,
    nodes: List<Node>,
    fetchedAt: Long,
) {
    val repository = cacheRepository ?: return
    repository.cacheResources(
        contextName = contextName,
        nodes = nodes.map { node ->
            CachedNode(
                contextName = contextName,
                name = node.name,
                status = if (node.ready) "READY" else "NOT_READY",
                lastFetchedAt = fetchedAt,
            )
        },
        fetchedAt = fetchedAt,
    )
}

private fun List<Node>.toLiveNodeListResult(
    fetchedAt: Long = System.currentTimeMillis(),
): NodeListResult = NodeListResult(
    nodes = this,
    staleDataIndicator = StaleDataIndicator(
        isFresh = true,
        lastFetchedAt = fetchedAt,
        source = StaleDataIndicator.Source.Live,
    ),
)

private fun nodeStaleIndicator(cachedNodes: List<CachedNode>): StaleDataIndicator {
    val lastFetchedAt = cachedNodes.maxOfOrNull { it.lastFetchedAt }
    val isFresh = lastFetchedAt != null && (System.currentTimeMillis() - lastFetchedAt) <= NODE_CACHE_TTL_MILLIS
    return StaleDataIndicator(
        isFresh = isFresh,
        lastFetchedAt = lastFetchedAt,
        source = StaleDataIndicator.Source.Cache,
    )
}

private fun CachedNode.toDomainNode(): Node = Node(
    name = name,
    roles = emptyList(),
    kubeletVersion = null,
    internalIp = null,
    externalIp = null,
    ready = status.equals("READY", ignoreCase = true),
    unschedulable = false,
    conditions = emptyList(),
    metrics = null,
)

private fun buildNodeApiClient(kubeConfigPath: Path): ApiClient {
    Files.newBufferedReader(kubeConfigPath).use { reader ->
        val kubeConfig = KubeConfig.loadKubeConfig(StringReader(reader.readText()))
        return ClientBuilder.kubeconfig(kubeConfig)
            .build()
            .setLenientOnJson(true)
    }
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

private val QUANTITY_PATTERN = Regex("^([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))(.*)$")

private val CPU_SUFFIX_FACTORS: Map<String, BigDecimal> = mapOf(
    "" to BigDecimal.ONE,
    "n" to BigDecimal("1e-9"),
    "u" to BigDecimal("1e-6"),
    "m" to BigDecimal("1e-3"),
    "k" to BigDecimal("1e3"),
    "K" to BigDecimal("1e3"),
    "M" to BigDecimal("1e6"),
    "G" to BigDecimal("1e9"),
    "T" to BigDecimal("1e12"),
    "P" to BigDecimal("1e15"),
    "E" to BigDecimal("1e18"),
)

private val MEMORY_SUFFIX_FACTORS: Map<String, BigDecimal> = mapOf(
    "" to BigDecimal.ONE,
    "Ki" to BigDecimal(1024),
    "Mi" to BigDecimal(1024).pow(2),
    "Gi" to BigDecimal(1024).pow(3),
    "Ti" to BigDecimal(1024).pow(4),
    "Pi" to BigDecimal(1024).pow(5),
    "Ei" to BigDecimal(1024).pow(6),
    "K" to BigDecimal(1000),
    "M" to BigDecimal(1000).pow(2),
    "G" to BigDecimal(1000).pow(3),
    "T" to BigDecimal(1000).pow(4),
    "P" to BigDecimal(1000).pow(5),
    "E" to BigDecimal(1000).pow(6),
)

private const val WATCH_TIMEOUT_SECONDS = 300
private const val NODE_CACHE_TTL_MILLIS = 120_000L
private const val DEFAULT_CONTEXT_NAME = "default"
