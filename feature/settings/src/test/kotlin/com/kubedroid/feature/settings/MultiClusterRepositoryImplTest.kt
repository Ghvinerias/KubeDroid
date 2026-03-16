package com.kubedroid.feature.settings

import com.kubedroid.core.network.kubeconfig.KubeConfig
import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import com.kubedroid.core.network.kubeconfig.KubeContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiClusterRepositoryImplTest {

    @Test
    fun getAllClusterSummaries_parallelFetch_emitsFastClusterBeforeSlowCluster() {
        val slowServer = ClusterApiTestServer(
            podCount = 2,
            nodeCount = 1,
            warningCount = 0,
            podListDelayMs = 1_200,
        ).start()
        val fastServer = ClusterApiTestServer(
            podCount = 5,
            nodeCount = 2,
            warningCount = 0,
        ).start()

        val kubeConfigPath = createKubeConfigFile(
            contexts = linkedMapOf(
                "alpha" to slowServer.baseUrl,
                "beta" to fastServer.baseUrl,
            ),
        )

        try {
            val repository = newRepository(
                kubeConfigPath = kubeConfigPath,
                contextNames = listOf("alpha", "beta"),
            )

            var firstEmission: List<ClusterSummary> = emptyList()
            var firstEmissionMs = Long.MAX_VALUE
            val allEmissions: List<List<ClusterSummary>>
            allEmissions = runBlocking {
                val startMs = System.currentTimeMillis()
                repository.getAllClusterSummaries()
                    .onEach { emission ->
                        if (firstEmission.isEmpty()) {
                            firstEmission = emission
                            firstEmissionMs = System.currentTimeMillis() - startMs
                        }
                    }
                    .toList()
            }
            val finalEmission = allEmissions.last()

            assertEquals(1, firstEmission.size)
            assertEquals("beta", firstEmission.single().contextName)
            assertEquals(2, finalEmission.size)
            assertEquals(listOf("alpha", "beta"), finalEmission.map { it.contextName })
            assertTrue("Expected fast first emission, actual=${firstEmissionMs}ms", firstEmissionMs < 900)
        } finally {
            Files.deleteIfExists(kubeConfigPath)
            slowServer.stop()
            fastServer.stop()
        }
    }

    @Test
    fun getAllClusterSummaries_oneUnreachableCluster_marksOnlyFailedClusterUnreachable() {
        val reachableServer = ClusterApiTestServer(
            podCount = 3,
            nodeCount = 1,
            warningCount = 0,
        ).start()

        val kubeConfigPath = createKubeConfigFile(
            contexts = linkedMapOf(
                "reachable" to reachableServer.baseUrl,
                "unreachable" to "http://127.0.0.1:1",
            ),
        )

        try {
            val repository = newRepository(
                kubeConfigPath = kubeConfigPath,
                contextNames = listOf("reachable", "unreachable"),
            )

            val emissions = runBlocking { repository.getAllClusterSummaries().toList() }
            val finalState = emissions.last()
            val healthByContext = finalState.associate { it.contextName to it.health }

            assertEquals(2, finalState.size)
            assertEquals(ClusterHealth.Healthy, healthByContext.getValue("reachable"))
            assertEquals(ClusterHealth.Unreachable, healthByContext.getValue("unreachable"))
        } finally {
            Files.deleteIfExists(kubeConfigPath)
            reachableServer.stop()
        }
    }

    @Test
    fun getAllClusterSummaries_allUnreachable_clustersMarkedUnreachable() {
        val kubeConfigPath = createKubeConfigFile(
            contexts = linkedMapOf(
                "cluster-a" to "http://127.0.0.1:1",
                "cluster-b" to "http://127.0.0.1:2",
            ),
        )

        try {
            val repository = newRepository(
                kubeConfigPath = kubeConfigPath,
                contextNames = listOf("cluster-a", "cluster-b"),
            )

            val emissions = runBlocking { repository.getAllClusterSummaries().toList() }
            val finalState = emissions.last()

            assertEquals(2, finalState.size)
            assertTrue(finalState.all { it.health == ClusterHealth.Unreachable })
            assertTrue(finalState.all { it.podCount == 0 && it.nodeCount == 0 && it.warningEventCount == 0 })
        } finally {
            Files.deleteIfExists(kubeConfigPath)
        }
    }

    private fun newRepository(
        kubeConfigPath: Path,
        contextNames: List<String>,
    ): MultiClusterRepositoryImpl {
        return MultiClusterRepositoryImpl(
            kubeConfigRepository = FakeKubeConfigRepository(contextNames),
            kubeConfigPath = kubeConfigPath,
        )
    }

    private fun createKubeConfigFile(contexts: LinkedHashMap<String, String>): Path {
        val file = Files.createTempFile("kubedroid-settings-test", ".yaml")
        val currentContext = contexts.keys.first()

        val clustersYaml = contexts.entries.joinToString("\n") { (context, serverUrl) ->
            """
            |  - name: ${context}-cluster
            |    cluster:
            |      server: $serverUrl
            |      insecure-skip-tls-verify: true
            """.trimMargin()
        }
        val usersYaml = contexts.keys.joinToString("\n") { context ->
            """
            |  - name: ${context}-user
            |    user:
            |      token: test-token
            """.trimMargin()
        }
        val contextsYaml = contexts.keys.joinToString("\n") { context ->
            """
            |  - name: $context
            |    context:
            |      cluster: ${context}-cluster
            |      user: ${context}-user
            """.trimMargin()
        }

        val yaml = """
            |apiVersion: v1
            |kind: Config
            |current-context: $currentContext
            |clusters:
            |$clustersYaml
            |users:
            |$usersYaml
            |contexts:
            |$contextsYaml
        """.trimMargin()

        Files.write(file, yaml.toByteArray())
        return file
    }
}

private class FakeKubeConfigRepository(
    contextNames: List<String>,
) : KubeConfigRepository {

    private val config = KubeConfig(
        contexts = contextNames.map { contextName ->
            KubeContext(
                name = contextName,
                cluster = "${contextName}-cluster",
                user = "${contextName}-user",
            )
        },
        currentContext = contextNames.firstOrNull(),
    )

    override suspend fun load(): Result<KubeConfig> = Result.success(config)

    override suspend fun save(config: KubeConfig): Result<Unit> =
        Result.failure(UnsupportedOperationException("Not used in tests"))

    override suspend fun delete(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Not used in tests"))

    override suspend fun listContexts(): Result<List<KubeContext>> =
        Result.success(config.contexts)

    override suspend fun setActiveContext(contextName: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Not used in tests"))
}

private class ClusterApiTestServer(
    private val podCount: Int,
    private val nodeCount: Int,
    private val warningCount: Int,
    private val podListDelayMs: Long = 0,
) {
    private val server = MockWebServer()

    val baseUrl: String
        get() = server.url("/").toString()

    fun start(): ClusterApiTestServer {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore("?")
                return when (path) {
                    "/api/v1/pods" -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(podListJson(podCount))
                            .addHeader("Content-Type", "application/json")
                            .apply {
                                if (podListDelayMs > 0) {
                                    setBodyDelay(podListDelayMs, TimeUnit.MILLISECONDS)
                                }
                            }
                    }

                    "/api/v1/nodes" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(nodeListJson(nodeCount))
                        .addHeader("Content-Type", "application/json")

                    "/api/v1/events" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(eventListJson(warningCount))
                        .addHeader("Content-Type", "application/json")

                    "/apis/metrics.k8s.io/v1beta1/nodes" -> MockResponse().setResponseCode(404)

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        return this
    }

    fun stop() {
        server.shutdown()
    }
}

private fun podListJson(count: Int): String = """
{
  "apiVersion": "v1",
  "kind": "PodList",
  "items": [${(1..count).joinToString(",") { "{}" }}]
}
""".trimIndent()

private fun nodeListJson(count: Int): String = """
{
  "apiVersion": "v1",
  "kind": "NodeList",
  "items": [${(1..count).joinToString(",") { "{}" }}]
}
""".trimIndent()

private fun eventListJson(count: Int): String = """
{
  "apiVersion": "v1",
  "kind": "EventList",
  "items": [${(1..count).joinToString(",") { "{}" }}]
}
""".trimIndent()
