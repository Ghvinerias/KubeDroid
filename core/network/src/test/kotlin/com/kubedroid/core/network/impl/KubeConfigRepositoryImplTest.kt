package com.kubedroid.core.network.impl

import com.kubedroid.core.network.kubeconfig.KubeCluster
import com.kubedroid.core.network.kubeconfig.KubeConfig
import com.kubedroid.core.network.kubeconfig.KubeContext
import com.kubedroid.core.network.kubeconfig.KubeUser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer

class KubeConfigRepositoryImplTest {

    @Test
    fun test_load_validKubeconfig_returnsParsedModel() = runTest {
        val file = Files.createTempFile("kubeconfig", ".yaml")
        Files.write(file, validKubeConfig().toByteArray())
        val repository = KubeConfigRepositoryImpl(kubeConfigPath = file)

        val result = repository.load()

        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals("dev", config.currentContext)
        assertEquals(1, config.contexts.size)
        assertEquals(1, config.clusters.size)
        assertEquals(1, config.users.size)
        assertEquals("dev", config.contexts.first().name)
        assertEquals("https://example.invalid", config.clusters.first().server)
        assertEquals("dev-user", config.users.first().name)
    }

    @Test
    fun test_load_invalidKubeconfig_returnsInvalidKubeConfigException() = runTest {
        val file = Files.createTempFile("kubeconfig", ".yaml")
        Files.write(file, "invalid: [".toByteArray())
        val repository = KubeConfigRepositoryImpl(kubeConfigPath = file)

        val result = repository.load()

        assertTrue(result.isFailure)
        assertIs<InvalidKubeConfigException>(result.exceptionOrNull())
    }

    @Test
    fun test_setActiveContext_missingContext_returnsFailure() = runTest {
        val file = Files.createTempFile("kubeconfig", ".yaml")
        Files.write(file, validKubeConfig().toByteArray())
        val repository = KubeConfigRepositoryImpl(kubeConfigPath = file)

        val result = repository.setActiveContext("prod")

        assertTrue(result.isFailure)
        assertIs<InvalidKubeConfigException>(result.exceptionOrNull())
    }

    @Test
    fun test_save_and_listContexts_roundTripSucceeds() = runTest {
        val file = Files.createTempFile("kubeconfig", ".yaml")
        val repository = KubeConfigRepositoryImpl(kubeConfigPath = file)

        val saveResult = repository.save(
            KubeConfig(
                contexts = listOf(KubeContext("dev", "dev-cluster", "dev-user", "default")),
                clusters = listOf(KubeCluster("dev-cluster", "https://127.0.0.1:6443")),
                users = listOf(KubeUser("dev-user")),
                currentContext = "dev",
            ),
        )
        val listResult = repository.listContexts()

        assertTrue(saveResult.isSuccess)
        assertTrue(listResult.isSuccess)
        assertEquals(1, listResult.getOrThrow().size)
        assertEquals("dev", listResult.getOrThrow().first().name)
    }

    @Test
    fun test_load_withMockWebServerBackedServerUrl_returnsSuccess() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val file = Files.createTempFile("kubeconfig", ".yaml")
            Files.write(file, validKubeConfig(server.url("/").toString()).toByteArray())
            val repository = KubeConfigRepositoryImpl(kubeConfigPath = file)

            val result = repository.load()

            assertTrue(result.isSuccess)
            assertEquals("dev", result.getOrThrow().contexts.first().name)
        } finally {
            server.shutdown()
        }
    }

    private fun validKubeConfig(serverUrl: String = "https://example.invalid"): String = """
        apiVersion: v1
        kind: Config
        current-context: dev
        contexts:
          - name: dev
            context:
              cluster: dev-cluster
              user: dev-user
              namespace: default
        clusters:
          - name: dev-cluster
            cluster:
              server: $serverUrl
        users:
          - name: dev-user
            user:
              token: token-value
    """.trimIndent()
}
