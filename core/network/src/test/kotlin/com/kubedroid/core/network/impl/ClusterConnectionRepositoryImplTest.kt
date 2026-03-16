package com.kubedroid.core.network.impl

import com.kubedroid.core.network.connection.ClusterConnectionState
import com.kubedroid.core.network.kubeconfig.KubeContext
import java.net.SocketTimeoutException
import java.nio.file.Files
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class ClusterConnectionRepositoryImplTest {

    @Test
    fun test_getState_initial_isDisconnected() = runTest {
        val file = Files.createTempFile("kubeconfig", ".yaml")
        Files.write(file, validKubeConfig("http://127.0.0.1:1").toByteArray())
        val repository = ClusterConnectionRepositoryImpl(file)

        assertIs<ClusterConnectionState.Disconnected>(repository.getState().first())
    }

    @Test
    fun test_connect_success_withMockWebServer_setsConnectedState() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path?.startsWith("/api/v1/namespaces") == true) {
                    MockResponse().setResponseCode(200).setBody(namespaceListJson())
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        try {
            val file = Files.createTempFile("kubeconfig", ".yaml")
            Files.write(file, validKubeConfig(server.url("/").toString()).toByteArray())
            val repository = ClusterConnectionRepositoryImpl(file)
            val context = KubeContext("dev", "dev-cluster", "dev-user", "default")

            val result = repository.connect(context)

            assertTrue(result.isSuccess)
            val state = repository.getState().first()
            assertIs<ClusterConnectionState.Connected>(state)
            assertEquals("dev", (state as ClusterConnectionState.Connected).contextName)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun test_connect_invalidConfig_returnsFailure() = runTest {
        val file = Files.createTempFile("kubeconfig", ".yaml")
        Files.write(file, "bad: [".toByteArray())
        val repository = ClusterConnectionRepositoryImpl(file)
        val context = KubeContext("dev", "dev-cluster", "dev-user", "default")

        val result = repository.connect(context)

        assertTrue(result.isFailure)
        assertIs<InvalidKubeConfigException>(result.exceptionOrNull())
        assertIs<ClusterConnectionState.Failed>(repository.getState().first())
    }

    @Test
    fun test_connect_unreachableApiServer_returnsUnreachableApiServerException() = runTest {
        val file = Files.createTempFile("kubeconfig", ".yaml")
        Files.write(file, validKubeConfig("http://127.0.0.1:1").toByteArray())
        val repository = ClusterConnectionRepositoryImpl(file)
        val context = KubeContext("dev", "dev-cluster", "dev-user", "default")

        val result = repository.connect(context)

        assertTrue(result.isFailure)
        assertIs<UnreachableApiServerException>(result.exceptionOrNull())
        assertIs<ClusterConnectionState.Failed>(repository.getState().first())
    }

    @Test
    fun test_connect_401_mapsExpiredTokenException() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path?.startsWith("/api/v1/namespaces") == true) {
                    MockResponse().setResponseCode(401).setBody("{\"message\":\"Unauthorized\"}")
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        try {
            val file = Files.createTempFile("kubeconfig", ".yaml")
            Files.write(file, validKubeConfig(server.url("/").toString()).toByteArray())
            val repository = ClusterConnectionRepositoryImpl(file)
            val context = KubeContext("dev", "dev-cluster", "dev-user", "default")

            val result = repository.connect(context)

            assertTrue(result.isFailure)
            assertIs<ExpiredTokenException>(result.exceptionOrNull())
            assertIs<ClusterConnectionState.Failed>(repository.getState().first())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun test_connect_timeout_mapsUnreachableApiServerException() = runTest {
        val repository = ClusterConnectionRepositoryImpl(Files.createTempFile("kubeconfig", ".yaml"))

        val method = repository::class.java.getDeclaredMethod("mapConnectionError", Throwable::class.java)
        method.isAccessible = true
        val mapped = method.invoke(repository, SocketTimeoutException("timeout"))

        assertIs<UnreachableApiServerException>(mapped)
    }

    @Test
    fun test_connect_tlsSelfSigned_mapsSelfSignedTlsException() = runTest {
        val repository = ClusterConnectionRepositoryImpl(Files.createTempFile("kubeconfig", ".yaml"))

        val method = repository::class.java.getDeclaredMethod("mapConnectionError", Throwable::class.java)
        method.isAccessible = true
        val mapped = method.invoke(repository, SSLException("self signed certificate"))

        assertIs<SelfSignedTlsException>(mapped)
    }

    @Test
    fun test_connect_tlsGeneric_mapsClusterConnectionException() = runTest {
        val repository = ClusterConnectionRepositoryImpl(Files.createTempFile("kubeconfig", ".yaml"))

        val method = repository::class.java.getDeclaredMethod("mapConnectionError", Throwable::class.java)
        method.isAccessible = true
        val mapped = method.invoke(repository, SSLException("tls failed"))

        assertIs<ClusterConnectionException>(mapped)
    }

    @Test
    fun test_disconnect_afterConnect_emitsDisconnected() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path?.startsWith("/api/v1/namespaces") == true) {
                    MockResponse().setResponseCode(200).setBody(namespaceListJson())
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        try {
            val file = Files.createTempFile("kubeconfig", ".yaml")
            Files.write(file, validKubeConfig(server.url("/").toString()).toByteArray())
            val repository = ClusterConnectionRepositoryImpl(file)
            val context = KubeContext("dev", "dev-cluster", "dev-user", "default")

            val connect = repository.connect(context)
            val disconnect = repository.disconnect()

            assertTrue(connect.isSuccess)
            assertTrue(disconnect.isSuccess)
            assertIs<ClusterConnectionState.Disconnected>(repository.getState().first())
        } finally {
            server.shutdown()
        }
    }

    private fun validKubeConfig(serverUrl: String): String = """
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
              insecure-skip-tls-verify: true
        users:
          - name: dev-user
            user:
              token: expired-token
    """.trimIndent()

    private fun namespaceListJson(): String = """
        {
          "kind": "NamespaceList",
          "apiVersion": "v1",
          "items": []
        }
    """.trimIndent()
}
