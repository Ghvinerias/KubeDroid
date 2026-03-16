package com.kubedroid.feature.metrics.impl

import android.content.Context
import com.kubedroid.feature.metrics.domain.model.ContainerMetrics
import com.kubedroid.feature.metrics.domain.model.MetricPoint
import com.kubedroid.feature.metrics.domain.model.ResourceMetrics
import com.kubedroid.feature.metrics.domain.repository.MetricsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CustomObjectsApi
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
@OptIn(ObsoleteCoroutinesApi::class)
class MetricsRepositoryImpl : MetricsRepository {
    private val metricsClient: MetricsClient
    private val ioDispatcher: CoroutineDispatcher
    private val pollIntervalMillis: Long
    private val historyLimit: Int
    private val tickerFactory: (Long, Long) -> ReceiveChannel<Unit>
    private val availabilityMutex = Mutex()
    private val historyMutex = Mutex()
    private var metricsServerAvailableCache: Boolean? = null
    private val podHistory = mutableMapOf<PodKey, MutableMap<String, ArrayDeque<MetricPoint>>>()

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        metricsClient = KubernetesMetricsClient(
            apiClientProvider = { buildMetricsApiClient(FilePaths.kubeConfigPath(context)) },
        ),
        ioDispatcher = Dispatchers.IO,
        pollIntervalMillis = DEFAULT_POLL_INTERVAL_MILLIS,
        historyLimit = DEFAULT_HISTORY_LIMIT,
        tickerFactory = { delayMillis, initialDelayMillis -> ticker(delayMillis = delayMillis, initialDelayMillis = initialDelayMillis) },
    )

    internal constructor(
        metricsClient: MetricsClient,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
        historyLimit: Int = DEFAULT_HISTORY_LIMIT,
        tickerFactory: (Long, Long) -> ReceiveChannel<Unit> = { delayMillis, initialDelayMillis ->
            ticker(delayMillis = delayMillis, initialDelayMillis = initialDelayMillis)
        },
    ) {
        this.metricsClient = metricsClient
        this.ioDispatcher = ioDispatcher
        this.pollIntervalMillis = pollIntervalMillis.coerceAtLeast(250L)
        this.historyLimit = historyLimit.coerceAtLeast(1)
        this.tickerFactory = tickerFactory
    }

    override suspend fun getPodMetrics(name: String, namespace: String): ResourceMetrics? = withContext(ioDispatcher) {
        val normalizedName = name.trim()
        val normalizedNamespace = namespace.trim()
        if (normalizedName.isEmpty() || normalizedNamespace.isEmpty()) return@withContext null
        if (!isMetricsServerAvailable()) return@withContext null

        val sampleList = runCatching { metricsClient.listPodMetrics(normalizedNamespace) }
            .getOrElse { throwable ->
                if (throwable.isMetricsServerUnavailable()) {
                    markMetricsServerUnavailable()
                }
                return@withContext null
            }
        val mapped = historyMutex.withLock {
            applySamplesToHistory(
                namespace = normalizedNamespace,
                samples = sampleList,
            )
        }
        mapped.firstOrNull { it.podName == normalizedName }
    }

    override suspend fun getNodeMetrics(nodeName: String): MetricPoint? = withContext(ioDispatcher) {
        if (nodeName.trim().isEmpty()) return@withContext null
        if (!isMetricsServerAvailable()) return@withContext null
        runCatching { metricsClient.getNodeMetric(nodeName.trim()) }
            .getOrElse { throwable ->
                if (throwable.isMetricsServerUnavailable()) {
                    markMetricsServerUnavailable()
                }
                null
            }
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    override fun watchPodMetrics(namespace: String): Flow<List<ResourceMetrics>> = channelFlow {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isEmpty()) {
            send(emptyList())
            return@channelFlow
        }

        val tickerChannel = tickerFactory(pollIntervalMillis, 0L)
        try {
            while (true) {
                try {
                    tickerChannel.receive()
                } catch (_: ClosedReceiveChannelException) {
                    break
                }

                if (!isMetricsServerAvailable()) {
                    send(emptyList())
                    continue
                }

                val podSamplesResult = runCatching { metricsClient.listPodMetrics(normalizedNamespace) }
                if (podSamplesResult.isFailure) {
                    val throwable = podSamplesResult.exceptionOrNull()
                    if (throwable != null && throwable.isMetricsServerUnavailable()) {
                        markMetricsServerUnavailable()
                    }
                    send(emptyList())
                    continue
                }
                val podSamples = podSamplesResult.getOrNull().orEmpty()

                val mapped = historyMutex.withLock {
                    applySamplesToHistory(
                        namespace = normalizedNamespace,
                        samples = podSamples,
                    )
                }
                send(mapped)
            }
        } finally {
            tickerChannel.cancel()
        }
    }.flowOn(ioDispatcher)

    override suspend fun isMetricsServerAvailable(): Boolean = withContext(ioDispatcher) {
        availabilityMutex.withLock {
            metricsServerAvailableCache ?: runCatching { metricsClient.isMetricsServerAvailable() }
                .getOrElse { false }
                .also { metricsServerAvailableCache = it }
        }
    }

    private suspend fun markMetricsServerUnavailable() {
        availabilityMutex.withLock {
            metricsServerAvailableCache = false
        }
        historyMutex.withLock {
            podHistory.clear()
        }
    }

    private fun applySamplesToHistory(
        namespace: String,
        samples: List<PodMetricsSample>,
    ): List<ResourceMetrics> {
        val activePodKeys = samples.map { it.key }.toSet()
        val removedPodKeys = podHistory.keys
            .filter { it.namespace == namespace && it !in activePodKeys }
        removedPodKeys.forEach { podHistory.remove(it) }

        samples.forEach { pod ->
            val containerHistory = podHistory.getOrPut(pod.key) { mutableMapOf() }
            val activeContainers = pod.containers.map { it.name }.toSet()
            containerHistory.keys.retainAll(activeContainers)

            pod.containers.forEach { container ->
                val point = MetricPoint(
                    timestamp = pod.timestampMillis,
                    cpuMillicores = container.cpuMillicores,
                    memoryBytes = container.memoryBytes,
                )
                val points = containerHistory.getOrPut(container.name) { ArrayDeque() }
                points.addLast(point)
                while (points.size > historyLimit) {
                    points.removeFirst()
                }
            }
        }

        return samples
            .sortedBy { it.podName }
            .map { pod ->
                val historyByContainer = podHistory[pod.key].orEmpty()
                ResourceMetrics(
                    podName = pod.podName,
                    namespace = pod.namespace,
                    containers = pod.containers
                        .sortedBy { it.name }
                        .map { container ->
                            ContainerMetrics(
                                name = container.name,
                                points = historyByContainer[container.name]?.toList().orEmpty(),
                            )
                        },
                )
            }
    }

    private object FilePaths {
        fun kubeConfigPath(context: Context): Path = context.filesDir.toPath().resolve("kube/config")
    }

    companion object {
        private const val DEFAULT_POLL_INTERVAL_MILLIS = 10_000L
        private const val DEFAULT_HISTORY_LIMIT = 60
    }
}

internal interface MetricsClient {
    fun isMetricsServerAvailable(): Boolean
    fun listPodMetrics(namespace: String): List<PodMetricsSample>
    fun getNodeMetric(nodeName: String): MetricPoint?
}

internal data class PodMetricsSample(
    val podName: String,
    val namespace: String,
    val timestampMillis: Long,
    val containers: List<ContainerSample>,
) {
    val key: PodKey = PodKey(namespace = namespace, podName = podName)
}

internal data class ContainerSample(
    val name: String,
    val cpuMillicores: Long,
    val memoryBytes: Long,
)

internal data class PodKey(
    val namespace: String,
    val podName: String,
)

internal class KubernetesMetricsClient(
    private val apiClientProvider: () -> ApiClient,
) : MetricsClient {

    override fun isMetricsServerAvailable(): Boolean {
        return runCatching {
            customObjectsApi().getAPIResources(METRICS_GROUP, METRICS_VERSION).execute()
            true
        }.getOrElse { throwable ->
            when ((throwable as? ApiException)?.code) {
                404, 503 -> false
                else -> false
            }
        }
    }

    override fun listPodMetrics(namespace: String): List<PodMetricsSample> {
        val payload = customObjectsApi()
            .listNamespacedCustomObject(METRICS_GROUP, METRICS_VERSION, namespace, PODS_PLURAL)
            .execute()
            .asMap()
            ?: return emptyList()
        val items = payload["items"].asList()
        return items.mapNotNull { item ->
            val metadata = item["metadata"].asMap() ?: return@mapNotNull null
            val name = metadata["name"].asString().orEmpty()
            val itemNamespace = metadata["namespace"].asString().orEmpty()
            if (name.isBlank() || itemNamespace.isBlank()) return@mapNotNull null

            val timestampMillis = item["timestamp"].asString().toEpochMillisOrNow()
            val containerSamples = item["containers"].asList().mapNotNull { container ->
                val containerName = container["name"].asString().orEmpty()
                val usage = container["usage"].asMap() ?: return@mapNotNull null
                val cpuMillicores = usage["cpu"].asQuantity().toCpuMillicores() ?: return@mapNotNull null
                val memoryBytes = usage["memory"].asQuantity().toMemoryBytes() ?: return@mapNotNull null
                ContainerSample(
                    name = containerName,
                    cpuMillicores = cpuMillicores,
                    memoryBytes = memoryBytes,
                )
            }
            PodMetricsSample(
                podName = name,
                namespace = itemNamespace,
                timestampMillis = timestampMillis,
                containers = containerSamples,
            )
        }
    }

    override fun getNodeMetric(nodeName: String): MetricPoint? {
        val payload = customObjectsApi()
            .getClusterCustomObject(METRICS_GROUP, METRICS_VERSION, NODES_PLURAL, nodeName)
            .execute()
            .asMap()
            ?: return null
        val usage = payload["usage"].asMap() ?: return null
        val cpuMillicores = usage["cpu"].asQuantity().toCpuMillicores() ?: return null
        val memoryBytes = usage["memory"].asQuantity().toMemoryBytes() ?: return null
        val timestamp = payload["timestamp"].asString().toEpochMillisOrNow()
        return MetricPoint(timestamp = timestamp, cpuMillicores = cpuMillicores, memoryBytes = memoryBytes)
    }

    private fun customObjectsApi(): CustomObjectsApi = CustomObjectsApi(apiClientProvider())

    private companion object {
        const val METRICS_GROUP = "metrics.k8s.io"
        const val METRICS_VERSION = "v1beta1"
        const val PODS_PLURAL = "pods"
        const val NODES_PLURAL = "nodes"
    }
}

private fun Any?.asMap(): Map<*, *>? = this as? Map<*, *>

private fun Any?.asList(): List<Map<*, *>> = (this as? List<*>)
    .orEmpty()
    .mapNotNull { it as? Map<*, *> }

private fun Any?.asString(): String? = this as? String

private fun Any?.asQuantity(): Quantity? {
    val raw = this as? String ?: return null
    return runCatching { Quantity(raw) }.getOrNull()
}

private fun String?.toEpochMillisOrNow(): Long {
    val raw = this ?: return System.currentTimeMillis()
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
}

private fun Throwable.isMetricsServerUnavailable(): Boolean {
    val apiCode = (this as? ApiException)?.code ?: return false
    return apiCode == 404 || apiCode == 503
}

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
        get() = CPU_SUFFIX_FACTORS[suffix]

    val memoryFactor: BigDecimal?
        get() = MEMORY_SUFFIX_FACTORS[suffix]
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

private fun BigDecimal.longValueExactOrNull(): Long? = runCatching { longValueExact() }.getOrNull()

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

private fun buildMetricsApiClient(kubeConfigPath: Path): ApiClient {
    Files.newBufferedReader(kubeConfigPath).use { reader ->
        val kubeConfig = KubeConfig.loadKubeConfig(StringReader(reader.readText()))
        return ClientBuilder.kubeconfig(kubeConfig)
            .build()
            .setLenientOnJson(true)
    }
}
