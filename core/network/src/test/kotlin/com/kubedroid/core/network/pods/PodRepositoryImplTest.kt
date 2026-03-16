package com.kubedroid.core.network.pods

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PodRepositoryImplTest {

    @Test
    fun test_listPods_emptyNamespace_returnsFailure() = runTest {
        val repository = PodRepositoryImpl(
            apiClientProvider = { ApiClient() },
            podApiFactory = PodApiFactory { FakePodApi() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.listPods("  ")

        assertTrue(result.isFailure)
        assertIs<EmptyNamespaceException>(result.exceptionOrNull())
    }

    @Test
    fun test_listPods_403_mapsToForbiddenNamespaceAccessException() = runTest {
        val repository = PodRepositoryImpl(
            apiClientProvider = { ApiClient() },
            podApiFactory = PodApiFactory {
                FakePodApi(
                    listOutcomes = ArrayDeque<Any>(
                        listOf(ApiException(403, "forbidden")),
                    ),
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.listPods("default")

        assertTrue(result.isFailure)
        assertIs<ForbiddenNamespaceAccessException>(result.exceptionOrNull())
    }

    @Test
    fun test_listPods_404_mapsToKubernetesPodApiException() = runTest {
        val repository = PodRepositoryImpl(
            apiClientProvider = { ApiClient() },
            podApiFactory = PodApiFactory {
                FakePodApi(
                    listOutcomes = ArrayDeque<Any>(
                        listOf(ApiException(404, "missing")),
                    ),
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.listPods("default")

        assertTrue(result.isFailure)
        assertIs<KubernetesPodApiException>(result.exceptionOrNull())
    }

    @Test
    fun test_deletePod_emptyPodName_returnsFailure() = runTest {
        val repository = PodRepositoryImpl(
            apiClientProvider = { ApiClient() },
            podApiFactory = PodApiFactory { FakePodApi() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.deletePod("default", "  ")

        assertTrue(result.isFailure)
        assertIs<EmptyPodNameException>(result.exceptionOrNull())
    }

    @Test
    fun test_deletePod_404_mapsToPodNotFound() = runTest {
        val repository = PodRepositoryImpl(
            apiClientProvider = { ApiClient() },
            podApiFactory = PodApiFactory {
                FakePodApi(
                    deleteOutcomes = ArrayDeque<Any>(
                        listOf(ApiException(404, "missing")),
                    ),
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.deletePod("default", "nginx")

        assertTrue(result.isFailure)
        assertIs<PodNotFoundException>(result.exceptionOrNull())
    }

    @Test
    fun test_deletePod_success_trimsNamespaceAndPodName() = runTest {
        var capturedNamespace: String? = null
        var capturedPodName: String? = null
        val repository = PodRepositoryImpl(
            apiClientProvider = { ApiClient() },
            podApiFactory = PodApiFactory {
                FakePodApi(
                    onDelete = { namespace, podName ->
                        capturedNamespace = namespace
                        capturedPodName = podName
                    },
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.deletePod("  default  ", "  nginx-123  ")

        assertTrue(result.isSuccess)
        assertEquals("default", capturedNamespace)
        assertEquals("nginx-123", capturedPodName)
    }

    @Test
    fun test_watchPods_emptyNamespace_returnsFailureImmediately() = runTest {
        val repository = PodRepositoryImpl(
            apiClientProvider = { ApiClient() },
            podApiFactory = PodApiFactory { FakePodApi() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val emissions = repository.watchPods("  ").take(1).toList()

        assertEquals(1, emissions.size)
        assertTrue(emissions[0].isFailure)
        assertIs<EmptyNamespaceException>(emissions[0].exceptionOrNull())
    }

    @Test
    fun test_watchPods_appliesAddedModifiedDeletedEvents() = runTest {
        val fakePodApi = FakePodApi(
            listOutcomes = ArrayDeque<Any>(
                listOf(PodListSnapshot(pods = listOf(pod("alpha")), resourceVersion = "1")),
            ),
            watchOutcomes = ArrayDeque<Any>(
                listOf(
                    FakePodWatchSession(
                        listOf(
                            PodWatchEvent(type = "ADDED", pod = pod("beta"), resourceVersion = "2"),
                            PodWatchEvent(type = "MODIFIED", pod = pod("alpha", PodStatus.Failed), resourceVersion = "3"),
                            PodWatchEvent(type = "BOOKMARK", pod = null, resourceVersion = "4"),
                            PodWatchEvent(type = "UNKNOWN", pod = pod("ignored"), resourceVersion = "5"),
                            PodWatchEvent(type = "DELETED", pod = pod("beta"), resourceVersion = "6"),
                        ),
                    ),
                ),
            ),
        )
        val repository = PodRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            podApiFactory = PodApiFactory { fakePodApi },
            watchReconnectInitialBackoffMillis = 10L,
            watchReconnectMaxBackoffMillis = 40L,
        )

        val emissions = repository.watchPods("default").take(4).toList()

        assertTrue(emissions.all { it.isSuccess })
        assertEquals(listOf("alpha"), emissions[0].getOrThrow().pods.map { it.name })
        assertEquals(listOf("alpha", "beta"), emissions[1].getOrThrow().pods.map { it.name })
        assertIs<PodStatus.Failed>(emissions[2].getOrThrow().pods.first { it.name == "alpha" }.status)
        assertEquals(listOf("alpha"), emissions[3].getOrThrow().pods.map { it.name })
    }

    @Test
    fun test_watchPods_403_returnsForbiddenAndStops() = runTest {
        val fakePodApi = FakePodApi(
            watchOutcomes = ArrayDeque<Any>(
                listOf(ApiException(403, "forbidden")),
            ),
        )
        val repository = PodRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            podApiFactory = PodApiFactory { fakePodApi },
            watchReconnectInitialBackoffMillis = 10L,
            watchReconnectMaxBackoffMillis = 40L,
        )

        val emissions = repository.watchPods("default").take(2).toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions[0].isSuccess)
        assertTrue(emissions[1].isFailure)
        assertIs<ForbiddenNamespaceAccessException>(emissions[1].exceptionOrNull())
    }

    @Test
    fun test_watchPods_disconnection_usesExponentialBackoff() = runTest {
        val openWatchTimestamps = mutableListOf<Long>()
        val fakePodApi = FakePodApi(
            watchOutcomes = ArrayDeque<Any>(
                listOf(
                    IOException("disconnect-1"),
                    IOException("disconnect-2"),
                ),
            ),
            onOpenWatch = { openWatchTimestamps += testScheduler.currentTime },
        )

        val repository = PodRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            podApiFactory = PodApiFactory { fakePodApi },
            watchReconnectInitialBackoffMillis = 10L,
            watchReconnectMaxBackoffMillis = 40L,
        )

        val emittedFailures = mutableListOf<Throwable>()
        val collectJob = launch {
            repository.watchPods("default").collect { result ->
                if (result.isFailure) {
                    emittedFailures += result.exceptionOrNull() ?: IllegalStateException("missing failure")
                }
            }
        }

        advanceTimeBy(29L)
        collectJob.cancel()

        assertEquals(listOf(0L, 10L), openWatchTimestamps)
        assertTrue(emittedFailures.size >= 2)
        assertIs<PodWatchDisconnectedException>(emittedFailures[0])
        assertIs<PodWatchDisconnectedException>(emittedFailures[1])
    }
}

private class FakePodApi(
    private val listSnapshot: PodListSnapshot = PodListSnapshot(emptyList(), resourceVersion = "1"),
    private val listOutcomes: ArrayDeque<Any> = ArrayDeque(),
    private val deleteOutcomes: ArrayDeque<Any> = ArrayDeque(),
    private val watchOutcomes: ArrayDeque<Any> = ArrayDeque(),
    private val onOpenWatch: (() -> Unit)? = null,
    private val onDelete: ((namespace: String, podName: String) -> Unit)? = null,
) : PodApi {
    override fun listPods(namespace: String): PodListSnapshot {
        val outcome = listOutcomes.removeFirstOrNull() ?: listSnapshot
        return when (outcome) {
            is PodListSnapshot -> outcome
            is Throwable -> throw outcome
            else -> throw IllegalStateException("Unsupported list outcome: ${outcome::class.simpleName}")
        }
    }

    override fun deletePod(namespace: String, podName: String) {
        onDelete?.invoke(namespace, podName)
        val outcome = deleteOutcomes.removeFirstOrNull() ?: Unit
        when (outcome) {
            is Unit -> Unit
            is Throwable -> throw outcome
            else -> throw IllegalStateException("Unsupported delete outcome: ${outcome::class.simpleName}")
        }
    }

    override fun openPodWatch(namespace: String, resourceVersion: String?): PodWatchSession {
        onOpenWatch?.invoke()
        val outcome = watchOutcomes.removeFirstOrNull()
            ?: throw IllegalStateException("No more watch outcomes configured")

        return when (outcome) {
            is Throwable -> throw outcome
            is PodWatchSession -> outcome
            else -> throw IllegalStateException("Unsupported watch outcome: ${outcome::class.simpleName}")
        }
    }
}

private class FakePodWatchSession(
    private val events: List<PodWatchEvent>,
) : PodWatchSession {
    override fun iterator(): Iterator<PodWatchEvent> = events.iterator()

    override fun close() = Unit
}

private fun pod(
    name: String,
    status: PodStatus = PodStatus.Running,
): Pod = Pod(
    name = name,
    namespace = "default",
    status = status,
    nodeName = null,
    startTimeEpochMillis = null,
    containers = emptyList(),
)
