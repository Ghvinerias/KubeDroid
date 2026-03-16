package com.kubedroid.feature.settings

import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import io.kubernetes.client.Metrics
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import java.io.StringReader
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.SSLException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

class MultiClusterRepositoryImpl @Inject constructor(
    private val kubeConfigRepository: KubeConfigRepository,
    private val kubeConfigPath: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MultiClusterRepository {

    override fun getAllClusterSummaries(): Flow<List<ClusterSummary>> = flow {
        val config = kubeConfigRepository.load().getOrElse { throwable ->
            throw throwable
        }

        val contexts = config.contexts
            .distinctBy { it.name }
            .sortedBy { it.name }

        if (contexts.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val rawKubeConfig = readRawKubeConfig()
        emitAll(
            channelFlow {
                supervisorScope {
                    contexts.forEach { context ->
                        launch(ioDispatcher) {
                            send(fetchSummary(rawKubeConfig = rawKubeConfig, context = context))
                        }
                    }
                }
            }.runningFold(emptyList<ClusterSummary>()) { current, incoming ->
                (current.filterNot { it.contextName == incoming.contextName } + incoming)
                    .sortedBy { it.contextName }
            }.drop(1),
        )
    }.flowOn(ioDispatcher)

    private suspend fun fetchSummary(
        rawKubeConfig: String,
        context: com.kubedroid.core.network.kubeconfig.KubeContext,
    ): ClusterSummary {
        val contextName = context.name
        val preferredNamespace = context.namespace?.takeIf { it.isNotBlank() } ?: DEFAULT_NAMESPACE
        return try {
            val apiClient = buildApiClient(rawKubeConfig = rawKubeConfig, contextName = contextName)
            val coreApi = CoreV1Api(apiClient)
            var isReachable = false
            var hasApiAccessErrors = false

            val pods = runCatching {
                coreApi.listPodForAllNamespaces().execute().items.orEmpty().also { isReachable = true }
            }.recoverCatching { throwable ->
                if (throwable is ApiException) {
                    if (throwable.isTransportFailure()) throw throwable
                    hasApiAccessErrors = true
                    isReachable = true
                    coreApi
                        .listNamespacedPod(preferredNamespace)
                        .execute()
                        .items
                        .orEmpty()
                } else {
                    throw throwable
                }
            }.getOrElse { throwable ->
                if (throwable is ApiException) {
                    if (throwable.isTransportFailure()) throw throwable
                    hasApiAccessErrors = true
                    isReachable = true
                    emptyList()
                } else {
                    throw throwable
                }
            }

            val nodes = runCatching {
                coreApi.listNode().execute().items.orEmpty().also { isReachable = true }
            }.getOrElse { throwable ->
                if (throwable is ApiException) {
                    if (throwable.isTransportFailure()) throw throwable
                    hasApiAccessErrors = true
                    isReachable = true
                    emptyList()
                } else {
                    throw throwable
                }
            }

            val warningEvents = runCatching {
                coreApi
                    .listEventForAllNamespaces()
                    .fieldSelector("type=Warning")
                    .execute()
                    .items
                    .orEmpty()
                    .also { isReachable = true }
            }.recoverCatching { throwable ->
                if (throwable is ApiException) {
                    if (throwable.isTransportFailure()) throw throwable
                    hasApiAccessErrors = true
                    isReachable = true
                    coreApi
                        .listNamespacedEvent(preferredNamespace)
                        .fieldSelector("type=Warning")
                        .execute()
                        .items
                        .orEmpty()
                } else {
                    throw throwable
                }
            }.getOrElse { throwable ->
                if (throwable is ApiException) {
                    if (throwable.isTransportFailure()) throw throwable
                    hasApiAccessErrors = true
                    isReachable = true
                    emptyList()
                } else {
                    throw throwable
                }
            }

            val metrics = try {
                Metrics(apiClient).getNodeMetrics().items.orEmpty()
            } catch (exception: Throwable) {
                if (exception is CancellationException) throw exception
                emptyList()
            }

            val totalCpuMillicores = nodes.sumOf { node ->
                node.status
                    ?.allocatable
                    .orEmpty()["cpu"]
                    .toCpuMillicores() ?: 0L
            }
            val totalMemoryBytes = nodes.sumOf { node ->
                node.status
                    ?.allocatable
                    .orEmpty()["memory"]
                    .toMemoryBytes() ?: 0L
            }

            val usedCpuMillicores = metrics.sumOf { metric ->
                metric.usage.orEmpty()["cpu"].toCpuMillicores() ?: 0L
            }
            val usedMemoryBytes = metrics.sumOf { metric ->
                metric.usage.orEmpty()["memory"].toMemoryBytes() ?: 0L
            }

            val cpuUsagePct = usedCpuMillicores.toPercentOf(totalCpuMillicores)
            val memoryUsagePct = usedMemoryBytes.toPercentOf(totalMemoryBytes)

            ClusterSummary(
                contextName = contextName,
                podCount = pods.size,
                nodeCount = nodes.size,
                warningEventCount = warningEvents.size,
                cpuUsagePct = cpuUsagePct,
                memoryUsagePct = memoryUsagePct,
                health = when {
                    !isReachable -> ClusterHealth.Unreachable
                    warningEvents.isNotEmpty() || hasApiAccessErrors -> ClusterHealth.Degraded
                    else -> ClusterHealth.Healthy
                },
            )
        } catch (exception: Throwable) {
            if (exception is CancellationException) throw exception
            ClusterSummary(
                contextName = contextName,
                podCount = 0,
                nodeCount = 0,
                warningEventCount = 0,
                cpuUsagePct = 0f,
                memoryUsagePct = 0f,
                health = ClusterHealth.Unreachable,
            )
        }
    }

    private companion object {
        const val DEFAULT_NAMESPACE = "default"
    }

    private fun readRawKubeConfig(): String {
        if (!Files.exists(kubeConfigPath)) {
            throw IllegalStateException("Kubeconfig file not found")
        }
        return Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    }

    private fun buildApiClient(
        rawKubeConfig: String,
        contextName: String,
    ): ApiClient {
        val kubeConfig = KubeConfig.loadKubeConfig(StringReader(rawKubeConfig))
        kubeConfig.setContext(contextName)
        return ClientBuilder.kubeconfig(kubeConfig)
            .build()
            .setLenientOnJson(true)
    }
}

private fun Quantity?.toCpuMillicores(): Long? {
    val raw = this?.toSuffixedString()?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val parsed = parseNumberAndSuffix(raw) ?: return null
    val cpuFactor = CPU_SUFFIX_FACTORS[parsed.suffix] ?: return null
    val baseCores = parsed.number.multiply(cpuFactor)
    val milliCores = baseCores.multiply(BigDecimal(1_000))
    return milliCores.setScale(0, RoundingMode.HALF_UP).longValueExactOrNull()
}

private fun Quantity?.toMemoryBytes(): Long? {
    val raw = this?.toSuffixedString()?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val parsed = parseNumberAndSuffix(raw) ?: return null
    val memoryFactor = MEMORY_SUFFIX_FACTORS[parsed.suffix] ?: return null
    val bytes = parsed.number.multiply(memoryFactor)
    return bytes.setScale(0, RoundingMode.HALF_UP).longValueExactOrNull()
}

private data class QuantityParse(
    val number: BigDecimal,
    val suffix: String,
)

private fun parseNumberAndSuffix(value: String): QuantityParse? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null

    val match = QUANTITY_PATTERN.matchEntire(trimmed) ?: return null
    val number = runCatching { BigDecimal(match.groupValues[1]) }.getOrNull() ?: return null
    return QuantityParse(number = number, suffix = match.groupValues[2])
}

private fun Long.toPercentOf(total: Long): Float {
    if (total <= 0L) return 0f
    return (toDouble() / total.toDouble() * 100.0).toFloat()
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

private fun ApiException.isTransportFailure(): Boolean {
    if (code == 0) return true
    return generateSequence(cause) { it.cause }.any { cause ->
        cause is UnknownHostException ||
            cause is ConnectException ||
            cause is NoRouteToHostException ||
            cause is SocketTimeoutException ||
            cause is SSLException
    }
}
