package com.kubedroid.core.network.portforward

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class PortForwardSessionImplTest {

    @Test
    fun create_failsWhenNamespaceIsBlank() = runBlocking {
        val exception = assertFailsWith<PortForwardSessionException> {
            PortForwardSessionImpl.create(
                request = validRequest(namespace = "   "),
            )
        }

        assertEquals("Namespace must not be empty", exception.message)
    }

    @Test
    fun create_failsWhenRemotePortIsOutOfRange() = runBlocking {
        val exception = assertFailsWith<PortForwardSessionException> {
            PortForwardSessionImpl.create(
                request = validRequest(remotePort = 65_536),
            )
        }

        assertEquals("Remote port must be in range 1..65535", exception.message)
    }

    @Test
    fun create_failsWhenLocalPortIsOutOfRange() = runBlocking {
        val exception = assertFailsWith<PortForwardSessionException> {
            PortForwardSessionImpl.create(
                request = validRequest(localPort = 65_536),
            )
        }

        assertEquals("Local port must be in range 0..65535", exception.message)
    }

    @Test
    fun create_failsWhenApiServerUrlIsInvalid() = runBlocking {
        val exception = assertFailsWith<PortForwardSessionException> {
            PortForwardSessionImpl.create(
                request = validRequest(apiServer = "not-a-valid-url"),
            )
        }

        assertTrue(exception.message?.contains("Invalid API server URL") == true)
    }

    @Test
    fun session_emitsFailedWhenErrorStreamReceivesMessage() {
        runBlocking {
            val serverSocket = ServerSocket(0)
            val errorPipe = java.io.PipedInputStream()
            val errorWriter = java.io.PipedOutputStream(errorPipe)
            val bridgeState = BridgeState(
                inbound = ByteArrayInputStream(ByteArray(0)),
                outbound = ByteArrayOutputStream(),
                error = errorPipe,
            )

            val session = createSessionForTest(
                serverSocket = serverSocket,
                bridgeState = bridgeState,
            )

            errorWriter.write("connection dropped\n".toByteArray())
            errorWriter.flush()

            val failed = withTimeout(2_000) {
                session.statusFlow.first { it is PortForwardStatus.Failed }
            }

            assertIs<PortForwardStatus.Failed>(failed)
            assertIs<PortForwardDisconnectedException>(failed.cause)
            assertEquals("connection dropped", failed.cause.message)

            session.close()
            runCatching { errorWriter.close() }
        }
    }

    @Test
    fun close_closesSocketAndBridge_andEmitsClosed() = runBlocking {
        val serverSocket = ServerSocket(0)
        val bridgeState = BridgeState(
            inbound = ByteArrayInputStream(ByteArray(0)),
            outbound = ByteArrayOutputStream(),
            error = ByteArrayInputStream(ByteArray(0)),
        )

        val session = createSessionForTest(
            serverSocket = serverSocket,
            bridgeState = bridgeState,
        )

        session.close()

        withTimeout(2_000) {
            session.statusFlow.first { it is PortForwardStatus.Closed }
        }

        assertTrue(serverSocket.isClosed)
        assertTrue(bridgeState.closed)
    }

    private fun createSessionForTest(
        serverSocket: ServerSocket,
        bridgeState: BridgeState,
    ): PortForwardSessionImpl {
        val bridgeClass = Class.forName("com.kubedroid.core.network.portforward.PortForwardStreamBridge")
        val bridgeProxy = Proxy.newProxyInstance(
            bridgeClass.classLoader,
            arrayOf(bridgeClass),
            BridgeInvocationHandler(bridgeState),
        )

        val constructor = PortForwardSessionImpl::class.java.getDeclaredConstructor(
            ServerSocket::class.java,
            bridgeClass,
            Int::class.javaPrimitiveType,
            kotlinx.coroutines.CoroutineDispatcher::class.java,
        )
        constructor.isAccessible = true

        return constructor.newInstance(
            serverSocket,
            bridgeProxy,
            serverSocket.localPort,
            Dispatchers.IO,
        ) as PortForwardSessionImpl
    }

    private fun validRequest(
        apiServer: String = "https://cluster.example.com",
        namespace: String = "default",
        podName: String = "api-123",
        remotePort: Int = 8080,
        localPort: Int = 0,
        bearerToken: String? = "token",
    ): PortForwardRequest {
        return PortForwardRequest(
            apiServer = apiServer,
            namespace = namespace,
            podName = podName,
            remotePort = remotePort,
            localPort = localPort,
            bearerToken = bearerToken,
        )
    }
}

private data class BridgeState(
    val inbound: InputStream,
    val outbound: OutputStream,
    val error: InputStream,
    var closed: Boolean = false,
)

private class BridgeInvocationHandler(
    private val state: BridgeState,
) : InvocationHandler {
    override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
        return when (method.name) {
            "getInbound" -> state.inbound
            "getOutbound" -> state.outbound
            "getError" -> state.error
            "close" -> {
                state.closed = true
                runCatching { state.inbound.close() }
                runCatching { state.outbound.close() }
                runCatching { state.error.close() }
                Unit
            }
            "toString" -> "FakePortForwardStreamBridge"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected method: ${method.name}")
        }
    }
}
