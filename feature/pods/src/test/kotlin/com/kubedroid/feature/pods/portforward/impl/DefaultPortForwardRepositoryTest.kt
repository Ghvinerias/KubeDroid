package com.kubedroid.feature.pods.portforward.impl

import com.kubedroid.core.network.portforward.PortForwardSessionException
import com.kubedroid.feature.pods.portforward.PodPortForwardRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DefaultPortForwardRepositoryTest {

    private val repository = DefaultPortForwardRepository()

    @Test
    fun createSession_returnsFailureResult_whenSessionCreationThrows() = runBlocking {
        val result = repository.createSession(
            request = validRequest(namespace = "   "),
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<PortForwardSessionException>(exception)
        assertEquals("Namespace must not be empty", exception.message)
    }

    @Test
    fun createSession_returnsFailureResult_forInvalidRemotePort() = runBlocking {
        val result = repository.createSession(
            request = validRequest(remotePort = 0),
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<PortForwardSessionException>(exception)
        assertEquals("Remote port must be in range 1..65535", exception.message)
    }

    private fun validRequest(
        apiServer: String = "https://cluster.example.com",
        namespace: String = "default",
        podName: String = "api-123",
        remotePort: Int = 8080,
        localPort: Int? = 18080,
        bearerToken: String? = "token",
    ): PodPortForwardRequest {
        return PodPortForwardRequest(
            apiServer = apiServer,
            namespace = namespace,
            podName = podName,
            remotePort = remotePort,
            localPort = localPort,
            bearerToken = bearerToken,
        )
    }
}
