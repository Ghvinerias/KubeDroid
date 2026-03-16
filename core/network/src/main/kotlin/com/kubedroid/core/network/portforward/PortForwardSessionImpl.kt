package com.kubedroid.core.network.portforward

import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import java.io.BufferedReader
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val LOOPBACK_HOST = "127.0.0.1"
private const val STREAM_BUFFER_SIZE = 8_192

/**
 * Parameters needed to establish a pod port-forward session.
 */
data class PortForwardRequest(
    val apiServer: String,
    val namespace: String,
    val podName: String,
    val remotePort: Int,
    val localPort: Int = 0,
    val bearerToken: String? = null,
)

class PortForwardSessionImpl private constructor(
    private val serverSocket: ServerSocket,
    private val bridge: PortForwardStreamBridge,
    override val localPort: Int,
    private val ioDispatcher: CoroutineDispatcher,
) : PortForwardSession {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val closed = AtomicBoolean(false)
    private val status = MutableStateFlow<PortForwardStatus>(PortForwardStatus.Starting)

    override val statusFlow: Flow<PortForwardStatus> = status.asStateFlow()

    init {
        status.value = PortForwardStatus.Active

        scope.launch {
            monitorPortForwardErrorStream()
        }

        scope.launch {
            acceptAndBridgeClient()
        }
    }

    override fun close() {
        closeInternal(failure = null)
    }

    private suspend fun acceptAndBridgeClient() {
        val socket = try {
            serverSocket.accept()
        } catch (_: SocketException) {
            if (!isClosed()) {
                closeInternal(PortForwardDisconnectedException("Port-forward listener stopped unexpectedly"))
            }
            return
        } catch (throwable: Throwable) {
            if (!isClosed()) {
                closeInternal(
                    PortForwardDisconnectedException(
                        message = throwable.message ?: "Failed to accept local port-forward connection",
                        cause = throwable,
                    ),
                )
            }
            return
        }

        withContext(ioDispatcher) {
            bridgeSocket(socket)
        }
    }

    private suspend fun bridgeSocket(socket: Socket) {
        socket.use { client ->
            val toRemote = scope.launch {
                copyStream(
                    input = client.getInputStream(),
                    output = bridge.outbound,
                    eofMessage = "Local client disconnected",
                )
            }
            val fromRemote = scope.launch {
                copyStream(
                    input = bridge.inbound,
                    output = client.getOutputStream(),
                    eofMessage = "Remote pod disconnected",
                )
            }

            val (completed, completionError) = waitForFirstCompletion(toRemote, fromRemote)
            if (!isClosed()) {
                completionError?.let { throwable ->
                    val mapped = when (throwable) {
                        is PortForwardDisconnectedException -> throwable
                        else -> PortForwardDisconnectedException(
                            message = throwable.message ?: "Port-forward connection disconnected",
                            cause = throwable,
                        )
                    }
                    closeInternal(mapped)
                }
            }
        }
    }

    private suspend fun waitForFirstCompletion(first: Job, second: Job): Pair<Job, Throwable?> {
        val winner = CompletableDeferred<Pair<Job, Throwable?>>()

        val firstHandle = first.invokeOnCompletion { throwable ->
            winner.complete(first to throwable)
        }
        val secondHandle = second.invokeOnCompletion { throwable ->
            winner.complete(second to throwable)
        }

        val completed = winner.await()
        firstHandle.dispose()
        secondHandle.dispose()

        when (completed.first) {
            first -> {
                second.cancel()
                second.join()
            }
            second -> {
                first.cancel()
                first.join()
            }
        }

        return completed
    }

    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        eofMessage: String,
    ) {
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        while (!isClosed()) {
            val read = try {
                input.read(buffer)
            } catch (throwable: Throwable) {
                throw PortForwardDisconnectedException(
                    message = throwable.message ?: "Port-forward stream read failed",
                    cause = throwable,
                )
            }

            if (read < 0) {
                throw PortForwardDisconnectedException(eofMessage, EOFException(eofMessage))
            }
            if (read == 0) continue

            try {
                output.write(buffer, 0, read)
                output.flush()
            } catch (throwable: Throwable) {
                throw PortForwardDisconnectedException(
                    message = throwable.message ?: "Port-forward stream write failed",
                    cause = throwable,
                )
            }
        }
    }

    private suspend fun monitorPortForwardErrorStream() {
        try {
            BufferedReader(InputStreamReader(bridge.error, StandardCharsets.UTF_8)).use { reader ->
                while (scope.isActive && !isClosed()) {
                    val line = reader.readLine() ?: break
                    val message = line.trim()
                    if (message.isNotEmpty()) {
                        closeInternal(PortForwardDisconnectedException(message))
                        return
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (!isClosed()) {
                closeInternal(
                    PortForwardDisconnectedException(
                        message = throwable.message ?: "Port-forward error channel disconnected",
                        cause = throwable,
                    ),
                )
            }
        }
    }

    private fun closeInternal(failure: Throwable?) {
        if (!closed.compareAndSet(false, true)) return

        if (failure != null) {
            status.value = PortForwardStatus.Failed(failure)
        }
        runCatching { serverSocket.close() }
        runCatching { bridge.close() }

        if (failure == null) {
            status.value = PortForwardStatus.Closed
        }
        scope.cancel()
    }

    private fun isClosed(): Boolean = closed.get()

    companion object {
        suspend fun create(
            request: PortForwardRequest,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): PortForwardSessionImpl = withContext(ioDispatcher) {
            val normalizedRequest = request.normalize()
            val apiClient = normalizedRequest.toApiClient()

            var bridge: PortForwardStreamBridge? = null
            var serverSocket: ServerSocket? = null

            try {
                ensurePodRunning(apiClient = apiClient, request = normalizedRequest)

                bridge = KubernetesPortForwardStreamBridge(
                    result = PortForward(apiClient).forward(
                        normalizedRequest.namespace,
                        normalizedRequest.podName,
                        listOf(normalizedRequest.remotePort),
                    ),
                    remotePort = normalizedRequest.remotePort,
                )

                serverSocket = ServerSocket().apply {
                    bind(
                        InetSocketAddress(
                            InetAddress.getByName(LOOPBACK_HOST),
                            normalizedRequest.localPort,
                        ),
                    )
                }

                PortForwardSessionImpl(
                    serverSocket = serverSocket,
                    bridge = bridge,
                    localPort = serverSocket.localPort,
                    ioDispatcher = ioDispatcher,
                )
            } catch (throwable: Throwable) {
                runCatching { serverSocket?.close() }
                runCatching { bridge?.close() }
                throw mapCreateError(
                    throwable = throwable,
                    namespace = normalizedRequest.namespace,
                    podName = normalizedRequest.podName,
                    requestedLocalPort = normalizedRequest.localPort,
                )
            }
        }

        private fun PortForwardRequest.normalize(): PortForwardRequest {
            val namespace = namespace.trim().ifEmpty {
                throw PortForwardSessionException("Namespace must not be empty")
            }
            val podName = podName.trim().ifEmpty {
                throw PortForwardSessionException("Pod name must not be empty")
            }
            val apiServer = apiServer.trim().ifEmpty {
                throw PortForwardSessionException("API server URL must not be empty")
            }
            val remotePort = remotePort
            if (remotePort !in 1..65_535) {
                throw PortForwardSessionException("Remote port must be in range 1..65535")
            }
            val localPort = localPort
            if (localPort !in 0..65_535) {
                throw PortForwardSessionException("Local port must be in range 0..65535")
            }
            val bearerToken = bearerToken?.trim()?.ifEmpty { null }

            return copy(
                apiServer = apiServer,
                namespace = namespace,
                podName = podName,
                remotePort = remotePort,
                localPort = localPort,
                bearerToken = bearerToken,
            )
        }

        private fun PortForwardRequest.toApiClient(): ApiClient {
            val url = apiServer.toHttpUrlOrNull()
                ?: throw PortForwardSessionException("Invalid API server URL: $apiServer")

            return ApiClient().apply {
                basePath = url.toString().removeSuffix("/")
                setVerifyingSsl(url.isHttps)
                bearerToken?.let { token ->
                    setApiKeyPrefix("Bearer")
                    setApiKey(token)
                }
            }
        }

        private fun ensurePodRunning(
            apiClient: ApiClient,
            request: PortForwardRequest,
        ) {
            val pod = CoreV1Api(apiClient)
                .readNamespacedPod(request.podName, request.namespace)
                .execute()
            val phase = pod.status?.phase?.trim().orEmpty()
            if (!phase.equals("Running", ignoreCase = true)) {
                throw PodNotRunningPortForwardException(
                    namespace = request.namespace,
                    podName = request.podName,
                    phase = phase.ifBlank { null },
                )
            }
        }

        private fun mapCreateError(
            throwable: Throwable,
            namespace: String,
            podName: String,
            requestedLocalPort: Int,
        ): Throwable {
            return when (throwable) {
                is PodNotRunningPortForwardException -> throwable
                is PortForwardAddressInUseException -> throwable
                is PortForwardSessionException -> throwable
                is BindException -> PortForwardAddressInUseException(
                    host = LOOPBACK_HOST,
                    port = requestedLocalPort,
                    cause = throwable,
                )
                is ApiException -> when (throwable.code) {
                    404 -> PortForwardSessionException(
                        "Pod '$podName' was not found in namespace '$namespace'",
                        throwable,
                    )
                    403 -> PortForwardSessionException(
                        "Forbidden: no permission to port-forward pod '$podName' in namespace '$namespace'",
                        throwable,
                    )
                    else -> PortForwardSessionException(
                        "Port-forward API returned HTTP ${throwable.code}",
                        throwable,
                    )
                }
                else -> PortForwardSessionException(
                    message = throwable.message ?: "Failed to create port-forward session",
                    cause = throwable,
                )
            }
        }
    }
}

private interface PortForwardStreamBridge : Closeable {
    val inbound: InputStream
    val outbound: OutputStream
    val error: InputStream
}

private class KubernetesPortForwardStreamBridge(
    result: PortForward.PortForwardResult,
    remotePort: Int,
) : PortForwardStreamBridge {

    override val inbound: InputStream = result.getInputStream(remotePort)
        ?: throw PortForwardSessionException("Failed to open remote input stream for port $remotePort")
    override val outbound: OutputStream = result.getOutboundStream(remotePort)
        ?: throw PortForwardSessionException("Failed to open remote output stream for port $remotePort")
    override val error: InputStream = result.getErrorStream(remotePort)
        ?: throw PortForwardSessionException("Failed to open remote error stream for port $remotePort")

    override fun close() {
        runCatching { inbound.close() }
        runCatching { outbound.close() }
        runCatching { error.close() }
    }
}

class PortForwardSessionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class PodNotRunningPortForwardException(
    namespace: String,
    podName: String,
    phase: String? = null,
) : Exception(
    if (phase.isNullOrBlank()) {
        "Pod '$podName' in namespace '$namespace' is not running"
    } else {
        "Pod '$podName' in namespace '$namespace' is not running (phase=$phase)"
    },
)

class PortForwardAddressInUseException(
    host: String,
    port: Int,
    cause: Throwable? = null,
) : Exception(
    if (port == 0) {
        "Failed to bind local port-forward listener on $host"
    } else {
        "Local address $host:$port is already in use"
    },
    cause,
)

class PortForwardDisconnectedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
