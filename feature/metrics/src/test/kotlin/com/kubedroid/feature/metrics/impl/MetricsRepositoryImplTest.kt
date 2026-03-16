package com.kubedroid.feature.metrics.impl

import com.kubedroid.feature.metrics.domain.model.MetricPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetricsRepositoryImplTest {

    @Test
    fun metricsAvailable_getPodAndNodeMetricsReturnData() = runTest {
        val client = object : MetricsClient {
            override fun isMetricsServerAvailable(): Boolean = true

            override fun listPodMetrics(namespace: String): List<PodMetricsSample> {
                return listOf(
                    PodMetricsSample(
                        podName = "pod-a",
                        namespace = namespace,
                        timestampMillis = 100L,
                        containers = listOf(
                            ContainerSample(
                                name = "main",
                                cpuMillicores = 250L,
                                memoryBytes = 8_388_608L,
                            ),
                        ),
                    ),
                )
            }

            override fun getNodeMetric(nodeName: String): MetricPoint? {
                return MetricPoint(
                    timestamp = 101L,
                    cpuMillicores = 600L,
                    memoryBytes = 32_000_000L,
                )
            }
        }
        val repo = repository(client = client)

        val podMetrics = repo.getPodMetrics(name = "pod-a", namespace = "default")
        val nodeMetrics = repo.getNodeMetrics("node-a")

        assertEquals(true, repo.isMetricsServerAvailable())
        assertEquals("pod-a", podMetrics?.podName)
        assertEquals(250L, podMetrics?.containers?.firstOrNull()?.points?.lastOrNull()?.cpuMillicores)
        assertEquals(600L, nodeMetrics?.cpuMillicores)
    }

    @Test
    fun watchPodMetrics_accumulatesAndCapsSparklineHistoryAt60Points() = runTest {
        val ticker = Channel<Unit>(capacity = Channel.UNLIMITED)
        val client = SequenceMetricsClient(
            podSamples = (1..61).map { value ->
                listOf(
                    PodMetricsSample(
                        podName = "pod-a",
                        namespace = "default",
                        timestampMillis = value.toLong(),
                        containers = listOf(
                            ContainerSample(
                                name = "main",
                                cpuMillicores = value.toLong(),
                                memoryBytes = (value * 1024L),
                            ),
                        ),
                    ),
                )
            },
        )

        val repo = repository(
            client = client,
            ticker = ticker,
        )

        val emissions = mutableListOf<List<com.kubedroid.feature.metrics.domain.model.ResourceMetrics>>()
        val collectJob = launch {
            repo.watchPodMetrics("default")
                .take(61)
                .toList(emissions)
        }

        repeat(61) { ticker.send(Unit) }
        collectJob.join()

        val points = emissions
            .last()
            .first()
            .containers
            .first()
            .points
        assertEquals(60, points.size)
        assertEquals(2L, points.first().cpuMillicores)
        assertEquals(61L, points.last().cpuMillicores)
    }

    @Test
    fun metricsServerAbsent_allEntryPointsReturnEmptyOrNull() = runTest {
        val ticker = Channel<Unit>(capacity = Channel.UNLIMITED)
        val client = object : MetricsClient {
            override fun isMetricsServerAvailable(): Boolean = false
            override fun listPodMetrics(namespace: String): List<PodMetricsSample> = error("Should not be called")
            override fun getNodeMetric(nodeName: String): MetricPoint? = error("Should not be called")
        }
        val repo = repository(client = client, ticker = ticker)

        ticker.send(Unit)
        assertTrue(repo.watchPodMetrics("default").first().isEmpty())
        assertNull(repo.getPodMetrics(name = "pod-a", namespace = "default"))
        assertNull(repo.getNodeMetrics("node-a"))
        assertEquals(false, repo.isMetricsServerAvailable())
    }

    @Test
    fun watchPodMetrics_switchesToEmptyWhenMetricsServerBecomesUnavailable() = runTest {
        val ticker = Channel<Unit>(capacity = Channel.UNLIMITED)
        val client = SequenceMetricsClient(
            podSamples = listOf(
                listOf(
                    PodMetricsSample(
                        podName = "pod-a",
                        namespace = "default",
                        timestampMillis = 1L,
                        containers = listOf(ContainerSample(name = "main", cpuMillicores = 5L, memoryBytes = 10L)),
                    ),
                ),
            ),
            failAfterSequences = true,
        )
        val repo = repository(client = client, ticker = ticker)

        val emissions = mutableListOf<List<com.kubedroid.feature.metrics.domain.model.ResourceMetrics>>()
        val collectJob = launch {
            repo.watchPodMetrics("default")
                .take(2)
                .toList(emissions)
        }

        ticker.send(Unit)
        ticker.send(Unit)
        collectJob.join()

        assertEquals(2, emissions.size)
        assertTrue(emissions.first().isNotEmpty())
        assertTrue(emissions.last().isEmpty())
    }

    private fun TestScope.repository(
        client: MetricsClient,
        ticker: Channel<Unit> = Channel(Channel.UNLIMITED),
    ): MetricsRepositoryImpl {
        return MetricsRepositoryImpl(
            metricsClient = client,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            pollIntervalMillis = 1_000L,
            historyLimit = 60,
            tickerFactory = { _, _ -> ticker },
        )
    }
}

private class SequenceMetricsClient(
    private val podSamples: List<List<PodMetricsSample>>,
    private val failAfterSequences: Boolean = false,
) : MetricsClient {
    private var callIndex = 0

    override fun isMetricsServerAvailable(): Boolean = true

    override fun listPodMetrics(namespace: String): List<PodMetricsSample> {
        val value = podSamples.getOrNull(callIndex)
        callIndex += 1
        if (value != null) return value
        if (failAfterSequences) {
            throw io.kubernetes.client.openapi.ApiException(404, "metrics api not found")
        }
        return podSamples.lastOrNull().orEmpty()
    }

    override fun getNodeMetric(nodeName: String): MetricPoint? = MetricPoint(
        timestamp = 1L,
        cpuMillicores = 1L,
        memoryBytes = 1L,
    )
}
