package com.kubedroid.core.network.config

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
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
class ConfigMapRepositoryImplTest {

    @Test
    fun test_list_returnsConfigMapsFromApi() = runTest {
        val fakeApi = FakeConfigMapApi(
            listSnapshot = ConfigMapListSnapshot(
                configMaps = listOf(
                    ConfigMap(
                        name = "app-config",
                        namespace = "default",
                        data = mapOf("LOG_LEVEL" to "debug"),
                        age = "1d",
                    ),
                ),
                resourceVersion = "5",
            ),
        )
        val repository = ConfigMapRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            configMapApiFactory = ConfigMapApiFactory { fakeApi },
        )

        val result = repository.list("default")

        assertTrue(result.isSuccess)
        assertEquals("app-config", result.getOrThrow().single().name)
        assertEquals("default", fakeApi.lastListNamespace)
    }

    @Test
    fun test_update_sendsNewDataAndReturnsUpdatedConfigMap() = runTest {
        val fakeApi = FakeConfigMapApi(
            listSnapshot = ConfigMapListSnapshot(emptyList(), resourceVersion = "1"),
            replaceResult = ConfigMap(
                name = "app-config",
                namespace = "default",
                data = mapOf("LOG_LEVEL" to "info"),
                age = "1d",
            ),
        )
        val repository = ConfigMapRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            configMapApiFactory = ConfigMapApiFactory { fakeApi },
        )

        val result = repository.update(
            ConfigMap(
                name = "app-config",
                namespace = "default",
                data = mapOf("LOG_LEVEL" to "info"),
                age = "ignored",
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(mapOf("LOG_LEVEL" to "info"), fakeApi.lastReplaceData)
        assertEquals("app-config", result.getOrThrow().name)
        assertEquals("default", fakeApi.lastReplaceNamespace)
        assertEquals("app-config", fakeApi.lastReplaceName)
    }

    @Test
    fun test_get_trimsInputsAndDelegatesToApi() = runTest {
        val fakeApi = FakeConfigMapApi(
            listSnapshot = ConfigMapListSnapshot(emptyList(), resourceVersion = "1"),
            getResult = ConfigMap(
                name = "app-config",
                namespace = "default",
                data = mapOf("LOG_LEVEL" to "debug"),
                age = "1d",
            ),
        )
        val repository = ConfigMapRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            configMapApiFactory = ConfigMapApiFactory { fakeApi },
        )

        val result = repository.get(name = "  app-config  ", namespace = "  default ")

        assertTrue(result.isSuccess)
        assertEquals("default", fakeApi.lastReadNamespace)
        assertEquals("app-config", fakeApi.lastReadName)
    }

    @Test
    fun test_watch_appliesAddedModifiedDeletedEvents() = runTest {
        val fakeApi = FakeConfigMapApi(
            listOutcomes = ArrayDeque(
                listOf(
                    ConfigMapListSnapshot(
                        configMaps = listOf(
                            ConfigMap("alpha", "default", mapOf("k" to "v1"), "1d"),
                        ),
                        resourceVersion = "1",
                    ),
                ),
            ),
            watchOutcomes = ArrayDeque(
                listOf(
                    FakeConfigMapWatchSession(
                        listOf(
                            ConfigMapWatchEvent(
                                type = "ADDED",
                                configMap = ConfigMap("beta", "default", mapOf("k" to "v2"), "1d"),
                                resourceVersion = "2",
                            ),
                            ConfigMapWatchEvent(
                                type = "MODIFIED",
                                configMap = ConfigMap("alpha", "default", mapOf("k" to "v3"), "1d"),
                                resourceVersion = "3",
                            ),
                            ConfigMapWatchEvent(type = "BOOKMARK", configMap = null, resourceVersion = "4"),
                            ConfigMapWatchEvent(type = "UNKNOWN", configMap = null, resourceVersion = "5"),
                            ConfigMapWatchEvent(
                                type = "DELETED",
                                configMap = ConfigMap("beta", "default", mapOf("k" to "v2"), "1d"),
                                resourceVersion = "6",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val repository = ConfigMapRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            watchReconnectInitialBackoffMillis = 10L,
            watchReconnectMaxBackoffMillis = 40L,
            configMapApiFactory = ConfigMapApiFactory { fakeApi },
        )

        val emissions = repository.watch("default").take(4).toList()

        assertTrue(emissions.all { it.isSuccess })
        assertEquals(listOf("alpha"), emissions[0].getOrThrow().map { it.name })
        assertEquals(listOf("alpha", "beta"), emissions[1].getOrThrow().map { it.name })
        assertEquals("v3", emissions[2].getOrThrow().first { it.name == "alpha" }.data["k"])
        assertEquals(listOf("alpha"), emissions[3].getOrThrow().map { it.name })
    }

    @Test
    fun test_watch_403_emitsFailureAndStopsWithoutReconnect() = runTest {
        val openWatchTimes = mutableListOf<Long>()
        val fakeApi = FakeConfigMapApi(
            watchOutcomes = ArrayDeque(listOf(ApiException(403, "forbidden"))),
            onOpenWatch = { openWatchTimes += testScheduler.currentTime },
        )
        val repository = ConfigMapRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            watchReconnectInitialBackoffMillis = 10L,
            watchReconnectMaxBackoffMillis = 40L,
            configMapApiFactory = ConfigMapApiFactory { fakeApi },
        )

        val emissions = repository.watch("default").take(2).toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions[0].isSuccess)
        assertTrue(emissions[1].isFailure)
        assertEquals(listOf(0L), openWatchTimes)
        val exception = emissions[1].exceptionOrNull()
        assertIs<ApiException>(exception)
        assertEquals(403, exception.code)
    }

    @Test
    fun test_watch_reconnectsWithBackoffAfterDisconnect() = runTest {
        val openWatchTimes = mutableListOf<Long>()
        val fakeApi = FakeConfigMapApi(
            watchOutcomes = ArrayDeque(
                listOf(
                    IllegalStateException("disconnect-1"),
                    IllegalStateException("disconnect-2"),
                ),
            ),
            onOpenWatch = { openWatchTimes += testScheduler.currentTime },
        )
        val repository = ConfigMapRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            watchReconnectInitialBackoffMillis = 10L,
            watchReconnectMaxBackoffMillis = 40L,
            configMapApiFactory = ConfigMapApiFactory { fakeApi },
        )

        val emittedFailures = mutableListOf<Throwable>()
        val collectJob = launch {
            repository.watch("default").collect { result ->
                if (result.isFailure) {
                    emittedFailures += result.exceptionOrNull() ?: IllegalStateException("missing failure")
                }
            }
        }

        advanceTimeBy(29L)
        collectJob.cancel()

        assertEquals(listOf(0L, 10L), openWatchTimes)
        assertTrue(emittedFailures.size >= 2)
    }
}

private class FakeConfigMapApi(
    private val listSnapshot: ConfigMapListSnapshot = ConfigMapListSnapshot(
        configMaps = emptyList(),
        resourceVersion = "1",
    ),
    private val getResult: ConfigMap = ConfigMap(
        name = "app-config",
        namespace = "default",
        data = emptyMap(),
        age = "1d",
    ),
    private val listOutcomes: ArrayDeque<Any> = ArrayDeque(),
    private val watchOutcomes: ArrayDeque<Any> = ArrayDeque(),
    private val onOpenWatch: (() -> Unit)? = null,
    private val replaceResult: ConfigMap = ConfigMap(
        name = "",
        namespace = "",
        data = emptyMap(),
        age = "unknown",
    ),
) : ConfigMapApi {
    var lastListNamespace: String? = null
    var lastReadNamespace: String? = null
    var lastReadName: String? = null
    var lastReplaceNamespace: String? = null
    var lastReplaceName: String? = null
    var lastReplaceData: Map<String, String>? = null

    override fun listConfigMapsSnapshot(namespace: String): ConfigMapListSnapshot {
        lastListNamespace = namespace
        val outcome = listOutcomes.removeFirstOrNull() ?: listSnapshot
        return when (outcome) {
            is ConfigMapListSnapshot -> outcome
            is Throwable -> throw outcome
            else -> throw IllegalStateException("Unsupported list outcome: ${outcome::class.simpleName}")
        }
    }

    override fun openConfigMapWatch(namespace: String, resourceVersion: String?): ConfigMapWatchSession {
        onOpenWatch?.invoke()
        val outcome = watchOutcomes.removeFirstOrNull()
            ?: throw IllegalStateException("No more watch outcomes configured")
        return when (outcome) {
            is ConfigMapWatchSession -> outcome
            is Throwable -> throw outcome
            else -> throw IllegalStateException("Unsupported watch outcome: ${outcome::class.simpleName}")
        }
    }

    override fun readConfigMap(namespace: String, name: String): ConfigMap {
        lastReadNamespace = namespace
        lastReadName = name
        return getResult
    }

    override fun replaceConfigMap(namespace: String, name: String, data: Map<String, String>): ConfigMap {
        lastReplaceNamespace = namespace
        lastReplaceName = name
        lastReplaceData = data
        return replaceResult
    }
}

private class FakeConfigMapWatchSession(
    private val events: List<ConfigMapWatchEvent>,
) : ConfigMapWatchSession {
    override fun iterator(): Iterator<ConfigMapWatchEvent> = events.iterator()
    override fun close() = Unit
}
