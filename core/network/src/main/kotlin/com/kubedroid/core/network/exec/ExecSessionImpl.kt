package com.kubedroid.core.network.exec

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import io.kubernetes.client.Exec
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import java.io.BufferedReader
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val UNKNOWN_EXIT_CODE = -1

class ExecSessionFactoryImpl(
    private val kubeConfigPath: Path? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExecSessionFactory {

    override suspend fun create(request: PodExecRequest): ExecSession = withContext(ioDispatcher) {
        val normalizedRequest = request.normalize()
        val apiClient = normalizedRequest.toApiClient()

        try {
            val podSnapshot = runCatching { readPodSnapshot(apiClient = apiClient, request = normalizedRequest) }.getOrNull()
            podSnapshot?.let { ensurePodRunning(it, normalizedRequest) }
            val resolvedRequest = normalizedRequest.withDefaultContainerFrom(podSnapshot)
            val process = runCatching {
                buildProcess(apiClient = apiClient, request = resolvedRequest)
            }.recoverCatching { throwable ->
                if (!normalizedRequest.containerName.isNullOrBlank() || !isContainerRequiredError(throwable)) {
                    throw throwable
                }
                val resolvedContainer = resolveDefaultContainerName(
                    apiClient = apiClient,
                    request = normalizedRequest,
                ) ?: throw throwable
                buildProcess(
                    apiClient = apiClient,
                    request = normalizedRequest.copy(containerName = resolvedContainer),
                )
            }.getOrThrow()
            ExecSessionImpl(
                process = process,
                ioDispatcher = ioDispatcher,
            )
        } catch (throwable: Throwable) {
            throw mapCreateError(
                throwable = throwable,
                namespace = normalizedRequest.namespace,
                podName = normalizedRequest.podName,
            )
        }
    }

    private fun readPodSnapshot(
        apiClient: ApiClient,
        request: PodExecRequest,
    ): JsonObject? {
        val call = CoreV1Api(apiClient)
            .readNamespacedPod(request.podName, request.namespace)
            .buildCall(null)
        return apiClient.execute<JsonObject>(
            call,
            object : TypeToken<JsonObject>() {}.type,
        ).data
    }

    private fun ensurePodRunning(
        pod: JsonObject,
        request: PodExecRequest,
    ) {
        val phase = pod
            .optObject("status")
            ?.optString("phase")
            ?.trim()
            .orEmpty()
        if (!phase.equals("Running", ignoreCase = true)) {
            throw PodNotRunningExecException(
                namespace = request.namespace,
                podName = request.podName,
                phase = phase.ifBlank { null },
            )
        }
    }

    private fun PodExecRequest.withDefaultContainerFrom(pod: JsonObject?): PodExecRequest {
        if (!containerName.isNullOrBlank()) return this
        val firstContainer = pod
            ?.optObject("spec")
            ?.optArray("containers")
            ?.firstObject()
            ?.optString("name")
            ?.trim()
            .orEmpty()
        return if (firstContainer.isBlank()) {
            this
        } else {
            copy(containerName = firstContainer)
        }
    }

    private fun resolveDefaultContainerName(
        apiClient: ApiClient,
        request: PodExecRequest,
    ): String? {
        return runCatching {
            readPodSnapshot(apiClient = apiClient, request = request)
                ?.optObject("spec")
                ?.optArray("containers")
                ?.firstObject()
                ?.optString("name")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun isContainerRequiredError(throwable: Throwable): Boolean {
        val apiException = throwable as? ApiException
        val message = listOfNotNull(
            apiException?.responseBody,
            apiException?.message,
            throwable.message,
        ).joinToString(separator = " ").lowercase()
        return apiException?.code == 400 &&
            (
                message.contains("container name must be specified") ||
                    message.contains("a container name is required") ||
                    message.contains("container not specified")
                )
    }

    private fun buildProcess(
        apiClient: ApiClient,
        request: PodExecRequest,
    ): Exec.ExecProcess {
        val builder = Exec(apiClient)
            .newExecutionBuilder(
                request.namespace,
                request.podName,
                request.command.toTypedArray(),
            )
            .setStdin(true)
            .setStdout(true)
            .setStderr(true)
            .setTty(true)
        request.containerName?.let(builder::setContainer)
        val process = builder.execute()

        return process as? Exec.ExecProcess
            ?: throw ExecSessionException("Kubernetes Exec did not return an ExecProcess instance")
    }

    private fun PodExecRequest.normalize(): PodExecRequest {
        val namespace = namespace.trim().ifEmpty {
            throw ExecSessionException("Namespace must not be empty")
        }
        val podName = podName.trim().ifEmpty {
            throw ExecSessionException("Pod name must not be empty")
        }
        val apiServer = apiServer.trim().ifEmpty {
            throw ExecSessionException("API server URL must not be empty")
        }
        val command = command
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { throw ExecSessionException("Exec command must not be empty") }
        val containerName = containerName?.trim()?.ifEmpty { null }
        val bearerToken = bearerToken?.trim()?.ifEmpty { null }

        return copy(
            apiServer = apiServer,
            namespace = namespace,
            podName = podName,
            containerName = containerName,
            command = command,
            bearerToken = bearerToken,
        )
    }

    private fun PodExecRequest.toApiClient(): ApiClient {
        val kubeConfigClient = kubeConfigPath?.let { path ->
            runCatching { buildApiClientFromKubeConfig(path) }.getOrNull()
        }
        if (kubeConfigClient != null) {
            return kubeConfigClient
        }

        val url = apiServer.toHttpUrlOrNull()
            ?: throw ExecSessionException("Invalid API server URL: $apiServer")

        return ApiClient().apply {
            basePath = url.toString().removeSuffix("/")
            setVerifyingSsl(url.isHttps)
            bearerToken?.let { token ->
                setApiKeyPrefix("Bearer")
                setApiKey(token)
            }
        }
    }

    private fun buildApiClientFromKubeConfig(kubeConfigPath: Path): ApiClient {
        if (!Files.exists(kubeConfigPath)) {
            throw ExecSessionException("Kubeconfig file not found at $kubeConfigPath")
        }
        val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
        val kubeConfig = try {
            KubeConfig.loadKubeConfig(StringReader(raw))
        } catch (throwable: Throwable) {
            throw ExecSessionException("Invalid kubeconfig content", throwable)
        }
        return ClientBuilder.kubeconfig(kubeConfig)
            .build()
            .setLenientOnJson(true)
    }

    private fun mapCreateError(
        throwable: Throwable,
        namespace: String,
        podName: String,
    ): Throwable {
        return when (throwable) {
            is PodNotRunningExecException -> throwable
            is ExecSessionException -> throwable
            is ApiException -> when (throwable.code) {
                403 -> ExecForbiddenException(namespace = namespace, podName = podName, cause = throwable)
                404 -> ExecSessionException("Pod '$podName' was not found in namespace '$namespace'", throwable)
                0 -> ExecSessionException(
                    message = "Exec API returned HTTP 0 (connection/TLS handshake failed). Verify cluster TLS certs and network reachability.",
                    cause = throwable,
                )
                else -> ExecSessionException(
                    message = "Exec API returned HTTP ${throwable.code}",
                    cause = throwable,
                )
            }
            else -> ExecSessionException(
                message = throwable.message ?: "Failed to create exec session",
                cause = throwable,
            )
        }
    }
}

class ExecSessionImpl(
    private val process: Exec.ExecProcess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExecSession {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val outputChannel = Channel<String>(capacity = Channel.BUFFERED)
    private val errorChannel = Channel<String>(capacity = Channel.BUFFERED)
    private val exitCodeChannel = Channel<Int>(capacity = 1)
    private val writeMutex = Mutex()
    private val resizeMutex = Mutex()
    private val closed = AtomicBoolean(false)

    override val outputFlow: Flow<String> = outputChannel.receiveAsFlow()
    override val errorFlow: Flow<String> = errorChannel.receiveAsFlow()
    override val exitCodeFlow: Flow<Int> = exitCodeChannel.receiveAsFlow()

    init {
        scope.launch {
            consumeInputStream(
                input = process.inputStream,
                emit = { outputChannel.send(it) },
            )
        }

        scope.launch {
            consumeInputStream(
                input = process.errorStream,
                emit = { errorChannel.send(it) },
            )
        }

        scope.launch {
            monitorConnectionErrorStream(
                input = process.connectionErrorStream,
            )
        }

        scope.launch {
            val code = waitForExitCode()
            emitExitCode(code)
            close()
        }
    }

    override suspend fun sendInput(input: String) = withContext(ioDispatcher) {
        if (isClosed()) return@withContext

        writeMutex.withLock {
            if (isClosed()) return@withLock
            try {
                process.outputStream
                    .write(input.toByteArray(StandardCharsets.UTF_8))
                process.outputStream.flush()
            } catch (_: IOException) {
                onDisconnected("Exec input stream closed")
            }
        }
    }

    override suspend fun resize(columns: Int, rows: Int) = withContext(ioDispatcher) {
        if (isClosed()) return@withContext
        if (columns <= 0 || rows <= 0) return@withContext

        resizeMutex.withLock {
            if (isClosed()) return@withLock
            val payload = "{\"Width\":$columns,\"Height\":$rows}"
            try {
                writeToStream(
                    stream = process.resizeStream,
                    bytes = payload.toByteArray(StandardCharsets.UTF_8),
                )
            } catch (_: IOException) {
                onDisconnected("Exec resize stream closed")
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        try {
            process.destroy()
        } catch (_: Throwable) {
            // Best-effort close.
        }

        outputChannel.close()
        errorChannel.close()
        exitCodeChannel.close()
        scope.cancel()
    }

    private suspend fun consumeInputStream(
        input: InputStream,
        emit: suspend (String) -> Unit,
    ) {
        try {
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(STREAM_BUFFER_SIZE)
                while (scope.isActive && !isClosed()) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    emit(String(buffer, 0, count))
                }
            }
        } catch (_: EOFException) {
            if (!isClosed()) {
                onDisconnected("Exec stream reached EOF")
            }
        } catch (_: IOException) {
            if (!isClosed()) {
                onDisconnected("Exec stream disconnected")
            }
        } catch (_: ClosedSendChannelException) {
            // Session already closed.
        }
    }

    private suspend fun monitorConnectionErrorStream(input: InputStream) {
        try {
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                while (scope.isActive && !isClosed()) {
                    val line = reader.readLine() ?: break
                    val message = line.trim()
                    if (message.isNotEmpty()) {
                        onDisconnected(message)
                        return
                    }
                }
            }
        } catch (_: IOException) {
            if (!isClosed() && process.isAlive) {
                onDisconnected("Exec connection lost")
            }
        }
    }

    private suspend fun waitForExitCode(): Int {
        return try {
            process.waitFor()
        } catch (_: InterruptedException) {
            UNKNOWN_EXIT_CODE
        } catch (_: Throwable) {
            UNKNOWN_EXIT_CODE
        }
    }

    private suspend fun emitExitCode(code: Int) {
        runCatching { exitCodeChannel.send(code) }
    }

    private suspend fun onDisconnected(message: String) {
        if (isClosed()) return
        runCatching { errorChannel.send(message) }
        emitExitCode(UNKNOWN_EXIT_CODE)
        close()
    }

    @Throws(IOException::class)
    private fun writeToStream(stream: OutputStream, bytes: ByteArray) {
        stream.write(bytes)
        stream.flush()
    }

    private fun isClosed(): Boolean = closed.get()

    companion object {
        private const val STREAM_BUFFER_SIZE = 4096
    }
}

class ExecSessionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class PodNotRunningExecException(
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

class ExecForbiddenException(
    namespace: String,
    podName: String,
    cause: Throwable? = null,
) : Exception(
    "Forbidden: no permission to exec into pod '$podName' in namespace '$namespace'",
    cause,
)

private fun JsonObject.optObject(name: String): JsonObject? {
    return if (has(name) && get(name).isJsonObject) getAsJsonObject(name) else null
}

private fun JsonObject.optArray(name: String): JsonArray? {
    return if (has(name) && get(name).isJsonArray) getAsJsonArray(name) else null
}

private fun JsonArray.firstObject(): JsonObject? {
    if (size() == 0) return null
    val value = get(0)
    return if (value != null && value.isJsonObject) value.asJsonObject else null
}

private fun JsonObject.optString(name: String): String? {
    if (!has(name)) return null
    val element = get(name)
    if (element == null || element.isJsonNull || !element.isJsonPrimitive) return null
    return runCatching { element.asString }.getOrNull()
}
