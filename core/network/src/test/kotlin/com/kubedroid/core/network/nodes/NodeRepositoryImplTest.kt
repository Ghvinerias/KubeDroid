package com.kubedroid.core.network.nodes

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class NodeRepositoryImplTest {

    @Test
    fun test_list_metricsUnavailable_gracefullyReturnsNodesWithoutMetrics() = runTest {
        val fakeNodeApi = FakeNodeApi(
            listNodesResult = NodeListSnapshot(
                nodes = listOf(nodeRecord(name = "node-a")),
                resourceVersion = "1",
            ),
            metricsOutcome = ApiException(404, "metrics api not found"),
        )
        val repository = NodeRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nodeApiFactory = NodeApiFactory { fakeNodeApi },
        )

        val result = repository.list()

        assertTrue(result.isSuccess)
        val nodes = result.getOrThrow().nodes
        assertEquals(1, nodes.size)
        assertEquals("node-a", nodes[0].name)
        assertNull(nodes[0].metrics)
    }

    @Test
    fun test_drainWithProgress_429RetriesUntilEvictionSucceeds() = runTest {
        val fakeNodeApi = FakeNodeApi(
            listPodsOnNodeResult = listOf(drainPod()),
            evictionOutcomes = ArrayDeque(
                listOf(
                    ApiException(429, "pdb blocking"),
                    Unit,
                ),
            ),
            podDeletedOutcomes = ArrayDeque(
                listOf(
                    false,
                    true,
                ),
            ),
        )
        val repository = NodeRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nodeApiFactory = NodeApiFactory { fakeNodeApi },
            podEvictionRetryBaseDelayMillis = 1L,
            podEvictionRetryMaxDelayMillis = 1L,
            podDeletionPollIntervalMillis = 1L,
            maxPodDeletionPolls = 3,
        )

        val emissions = repository.drainWithProgress("node-a").toList()

        assertIs<NodeDrainProgress.Started>(emissions[0])
        assertIs<NodeDrainProgress.EvictionAttempt>(emissions[1])
        assertIs<NodeDrainProgress.WaitingOnDisruptionBudget>(emissions[2])
        assertIs<NodeDrainProgress.EvictionAttempt>(emissions[3])
        assertIs<NodeDrainProgress.PodEvicted>(emissions[4])
        val completed = emissions.last()
        assertIs<NodeDrainProgress.Completed>(completed)
        assertIs<NodeActionResult.Success>(completed.result)
    }

    @Test
    fun test_drainWithProgress_403ReturnsForbidden() = runTest {
        val fakeNodeApi = FakeNodeApi(
            listPodsOnNodeResult = listOf(drainPod()),
            evictionOutcomes = ArrayDeque(
                listOf(ApiException(403, "forbidden")),
            ),
        )
        val repository = NodeRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nodeApiFactory = NodeApiFactory { fakeNodeApi },
        )

        val emissions = repository.drainWithProgress("node-a").toList()

        val completed = emissions.last()
        assertIs<NodeDrainProgress.Completed>(completed)
        assertEquals(NodeActionResult.Forbidden, completed.result)
    }

    @Test
    fun test_drain_returnsFinalCompletedResult() = runTest {
        val fakeNodeApi = FakeNodeApi(
            listPodsOnNodeResult = listOf(drainPod()),
            evictionOutcomes = ArrayDeque(listOf(Unit)),
            podDeletedOutcomes = ArrayDeque(listOf(true)),
        )
        val repository = NodeRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nodeApiFactory = NodeApiFactory { fakeNodeApi },
            podDeletionPollIntervalMillis = 1L,
        )

        val result = repository.drain("node-a")

        assertIs<NodeActionResult.Success>(result)
    }

    @Test
    fun test_drainWithProgress_blankNodeName_returnsFailure() = runTest {
        val repository = NodeRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nodeApiFactory = NodeApiFactory { FakeNodeApi() },
        )

        val emissions = repository.drainWithProgress("   ").toList()

        assertEquals(1, emissions.size)
        val completed = assertIs<NodeDrainProgress.Completed>(emissions.single())
        val failure = assertIs<NodeActionResult.Failure>(completed.result)
        assertIs<IllegalArgumentException>(failure.cause)
    }

    @Test
    fun test_drainWithProgress_podNeverDeletes_returnsConflict() = runTest {
        val fakeNodeApi = FakeNodeApi(
            listPodsOnNodeResult = listOf(drainPod()),
            evictionOutcomes = ArrayDeque(listOf(Unit)),
            podDeletedOutcomes = ArrayDeque(listOf(false, false, false)),
        )
        val repository = NodeRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nodeApiFactory = NodeApiFactory { fakeNodeApi },
            podDeletionPollIntervalMillis = 1L,
            maxPodDeletionPolls = 3,
        )

        val emissions = repository.drainWithProgress("node-a").toList()

        assertIs<NodeDrainProgress.Started>(emissions.first())
        val completed = assertIs<NodeDrainProgress.Completed>(emissions.last())
        assertEquals(NodeActionResult.Conflict, completed.result)
    }
}

private class FakeNodeApi(
    private val listNodesResult: NodeListSnapshot = NodeListSnapshot(emptyList(), resourceVersion = "1"),
    private val metricsOutcome: Any = emptyMap<String, NodeRawMetrics>(),
    private val listPodsOnNodeResult: List<DrainTargetPod> = emptyList(),
    private val evictionOutcomes: ArrayDeque<Any> = ArrayDeque(),
    private val podDeletedOutcomes: ArrayDeque<Any> = ArrayDeque(),
) : NodeApi {

    override fun listNodes(): NodeListSnapshot = listNodesResult

    override fun openNodeWatch(resourceVersion: String?): NodeWatchSession {
        throw AssertionError("watch is not used in these tests")
    }

    override fun patchNodeUnschedulable(nodeName: String, unschedulable: Boolean) {
        throw AssertionError("patch is not used in these tests")
    }

    override fun listPodsOnNode(nodeName: String): List<DrainTargetPod> = listPodsOnNodeResult

    override fun evictPod(namespace: String, podName: String) {
        val outcome = evictionOutcomes.removeFirstOrNull() ?: Unit
        when (outcome) {
            is Unit -> Unit
            is Throwable -> throw outcome
            else -> throw IllegalStateException("Unsupported eviction outcome: ${outcome::class.simpleName}")
        }
    }

    override fun isPodDeleted(namespace: String, podName: String): Boolean {
        val outcome = podDeletedOutcomes.removeFirstOrNull() ?: true
        return when (outcome) {
            is Boolean -> outcome
            is Throwable -> throw outcome
            else -> throw IllegalStateException("Unsupported pod deletion outcome: ${outcome::class.simpleName}")
        }
    }

    override fun listNodeMetricsOrNull(): Map<String, NodeRawMetrics> {
        return when (metricsOutcome) {
            is Map<*, *> -> metricsOutcome as Map<String, NodeRawMetrics>
            is ApiException -> {
                if (metricsOutcome.code in setOf(403, 404, 503)) {
                    emptyMap()
                } else {
                    throw metricsOutcome
                }
            }
            is Throwable -> throw metricsOutcome
            else -> throw IllegalStateException("Unsupported metrics outcome: ${metricsOutcome::class.simpleName}")
        }
    }
}

private fun nodeRecord(name: String): NodeRecord = NodeRecord(
    name = name,
    roles = listOf("worker"),
    kubeletVersion = "v1.30.0",
    internalIp = "10.0.0.10",
    externalIp = null,
    ready = true,
    unschedulable = false,
    conditions = emptyList(),
    allocatableCpuMillicores = 2000,
    allocatableMemoryBytes = 4L * 1024L * 1024L * 1024L,
)

private fun drainPod(): DrainTargetPod = DrainTargetPod(
    namespace = "default",
    name = "nginx-abc",
    isMirrorPod = false,
    isDaemonSetManaged = false,
    phase = "Running",
)
