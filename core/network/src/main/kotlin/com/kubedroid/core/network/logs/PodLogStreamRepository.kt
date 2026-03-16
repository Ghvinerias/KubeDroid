package com.kubedroid.core.network.logs

import com.google.gson.JsonParser
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.StringReader
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Network-backed [LogRepository] for Kubernetes pod log streaming via WebSocket.
 */
class LogStreamRepositoryImpl(
    private val okHttpClient: OkHttpClient,
    private val kubeConfigPath: Path? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LogRepository, LogStreamRepository {

    private val streamBus = ConcurrentHashMap<String, MutableSharedFlow<Result<LogLine>>>()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val calls = ConcurrentHashMap<String, Call>()
    @Volatile
    private var activeStreamId: String? = null

    override fun streamLogs(config: LogStreamConfig): Flow<LogLine> {
        return streamPodLogs(
            request = PodLogRequest(
                streamId = config.streamId,
                apiServer = config.apiServer,
                namespace = config.namespace,
                podName = config.podName,
                containerName = config.containerName,
                bearerToken = config.bearerToken,
                tailLines = config.tailLines,
                sinceSeconds = config.sinceSeconds,
                follow = config.follow,
            ),
        ).map { result -> result.getOrThrow() }
    }

    override fun stopStream() {
        val streamId = activeStreamId ?: return
        sockets.remove(streamId)?.cancel()
        calls.remove(streamId)?.cancel()
        streamBus.remove(streamId)
        activeStreamId = null
    }

    override fun streamPodLogs(request: PodLogRequest): Flow<Result<LogLine>> = callbackFlow {
        activeStreamId = request.streamId
        val channel = streamBus.getOrPut(request.streamId) {
            MutableSharedFlow(extraBufferCapacity = 256)
        }

        val collectorJob = launch {
            channel.collect { lineResult -> trySend(lineResult) }
        }

        val resolvedRequest = resolveDefaultContainer(request)
        val wsRequest = buildWebSocketRequest(resolvedRequest)
        if (wsRequest == null) {
            channel.tryEmit(Result.failure(LogStreamException(LogStreamError.Unknown("Invalid API server URL"))))
            close()
            collectorJob.cancel()
            return@callbackFlow
        }

        val host = resolvedRequest.apiServer.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val useWebSocket = isLocalHost(host)
        val streamClient = resolveTransportClient(resolvedRequest)
        if (useWebSocket) {
            val socket = streamClient
                .newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
                .newWebSocket(
                    wsRequest,
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            emitTextPayload(
                                streamId = resolvedRequest.streamId,
                                payload = text,
                                source = LogLine.Source.STDOUT,
                                channel = channel,
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            if (bytes.size == 0) {
                                return
                            }
                            val source = bytes[0].toSource()
                            val payload = if (bytes.size > 1) {
                                bytes.substring(1).utf8()
                            } else {
                                ""
                            }
                            emitTextPayload(
                                streamId = resolvedRequest.streamId,
                                payload = payload,
                                source = source,
                                channel = channel,
                            )
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            channel.tryEmit(Result.failure(LogStreamException(mapError(t, response))))
                        }
                    },
                )
            sockets[resolvedRequest.streamId] = socket
        } else {
            val httpRequest = buildHttpLogRequest(resolvedRequest)
            if (httpRequest == null) {
                channel.tryEmit(Result.failure(LogStreamException(LogStreamError.Unknown("Invalid API server URL"))))
                close()
                collectorJob.cancel()
                return@callbackFlow
            }
            val call = streamClient
                .newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
                .newCall(httpRequest)
            calls[resolvedRequest.streamId] = call
            launch(ioDispatcher) {
                runCatching {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            channel.tryEmit(
                                Result.failure(
                                    LogStreamException(
                                        mapError(
                                            throwable = IOException("HTTP ${response.code}"),
                                            response = response,
                                        ),
                                    ),
                                ),
                            )
                            return@use
                        }

                        val source = response.body?.source() ?: return@use
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            emitTextPayload(
                                streamId = resolvedRequest.streamId,
                                payload = line,
                                source = LogLine.Source.STDOUT,
                                channel = channel,
                            )
                        }
                    }
                }.onFailure { throwable ->
                    if (!call.isCanceled()) {
                        channel.tryEmit(Result.failure(LogStreamException(mapError(throwable, null))))
                    }
                }
            }
        }

        awaitClose {
            sockets.remove(resolvedRequest.streamId)?.cancel()
            calls.remove(resolvedRequest.streamId)?.cancel()
            collectorJob.cancel()
            streamBus.remove(resolvedRequest.streamId)
        }
    }
        .flowOn(ioDispatcher)

    override suspend fun stopStream(streamId: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            sockets.remove(streamId)?.cancel()
            calls.remove(streamId)?.cancel()
            streamBus.remove(streamId)
            if (activeStreamId == streamId) {
                activeStreamId = null
            }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(LogStreamException(LogStreamError.Unknown(it.message))) },
        )
    }

    private fun resolveTransportClient(request: PodLogRequest): OkHttpClient {
        val kubePath = kubeConfigPath ?: return okHttpClient
        return runCatching { buildKubeConfigClient(kubePath, request.apiServer) }.getOrElse { okHttpClient }
    }

    private fun buildKubeConfigClient(path: Path, apiServer: String): OkHttpClient {
        if (!Files.exists(path)) return okHttpClient
        val raw = Files.readAllBytes(path).toString(Charsets.UTF_8)
        val kubeConfig = KubeConfig.loadKubeConfig(StringReader(raw))
        selectContextForServer(kubeConfig, apiServer)?.let(kubeConfig::setContext)
        return ClientBuilder.kubeconfig(kubeConfig)
            .build()
            .setLenientOnJson(true)
            .httpClient
    }

    private fun selectContextForServer(kubeConfig: KubeConfig, apiServer: String): String? {
        val targetServer = normalizeServer(apiServer) ?: return null
        val contextByCluster = kubeConfig.contexts
            .mapNotNull { entry ->
                val named = entry as? Map<*, *> ?: return@mapNotNull null
                val contextName = named["name"] as? String ?: return@mapNotNull null
                val context = named["context"] as? Map<*, *> ?: return@mapNotNull null
                val clusterName = context["cluster"] as? String ?: return@mapNotNull null
                contextName to clusterName
            }
            .toMap()
        val serverByCluster = kubeConfig.clusters
            .mapNotNull { entry ->
                val named = entry as? Map<*, *> ?: return@mapNotNull null
                val clusterName = named["name"] as? String ?: return@mapNotNull null
                val cluster = named["cluster"] as? Map<*, *> ?: return@mapNotNull null
                val normalized = normalizeServer(cluster["server"] as? String) ?: return@mapNotNull null
                clusterName to normalized
            }
            .toMap()
        return contextByCluster.entries
            .firstOrNull { (_, clusterName) -> serverByCluster[clusterName] == targetServer }
            ?.key
    }

    private fun emitTextPayload(
        streamId: String,
        payload: String,
        source: LogLine.Source,
        channel: MutableSharedFlow<Result<LogLine>>,
    ) {
        payload.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { rawLine ->
                val (timestamp, message) = splitTimestamp(rawLine)
                val detectedSource = detectSourceFromLinePrefix(message, source)
                channel.tryEmit(
                    Result.success(
                        LogLine(
                            streamId = streamId,
                            timestamp = timestamp,
                            message = detectedSource.second,
                            source = detectedSource.first,
                            isError = detectedSource.first == LogLine.Source.STDERR,
                        ),
                    ),
                )
            }
    }

    private fun buildWebSocketRequest(request: PodLogRequest): Request? {
        val baseUrl = request.apiServer.toHttpUrlOrNull() ?: return null
        // OkHttp HttpUrl.Builder only accepts http/https; WebSocket upgrade happens in newWebSocket.
        val wsScheme = if (baseUrl.isHttps) "https" else "http"
        val basePathPrefix = baseUrl.encodedPath.let { path ->
            if (path == "/") "" else path.removeSuffix("/")
        }
        val logPath = "$basePathPrefix/api/v1/namespaces/${request.namespace}/pods/${request.podName}/log"
        val url = baseUrl.newBuilder()
            .scheme(wsScheme)
            .encodedPath(logPath)
            .addQueryParameter("follow", request.follow.toString())
            .addQueryParameter("timestamps", "true")
            .apply {
                request.containerName?.let { addQueryParameter("container", it) }
                request.tailLines?.let { addQueryParameter("tailLines", it.toString()) }
                request.sinceSeconds?.let { addQueryParameter("sinceSeconds", it.toString()) }
            }
            .build()

        return Request.Builder()
            .url(url)
            .apply {
                request.bearerToken?.let { token ->
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
    }

    private fun buildHttpLogRequest(request: PodLogRequest): Request? {
        val baseUrl = request.apiServer.toHttpUrlOrNull() ?: return null
        val basePathPrefix = baseUrl.encodedPath.let { path ->
            if (path == "/") "" else path.removeSuffix("/")
        }
        val logPath = "$basePathPrefix/api/v1/namespaces/${request.namespace}/pods/${request.podName}/log"
        val url = baseUrl.newBuilder()
            .encodedPath(logPath)
            .addQueryParameter("follow", request.follow.toString())
            .addQueryParameter("timestamps", "true")
            .apply {
                request.containerName?.let { addQueryParameter("container", it) }
                request.tailLines?.let { addQueryParameter("tailLines", it.toString()) }
                request.sinceSeconds?.let { addQueryParameter("sinceSeconds", it.toString()) }
            }
            .build()

        return Request.Builder()
            .url(url)
            .get()
            .apply {
                request.bearerToken?.let { token ->
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
    }

    private suspend fun resolveDefaultContainer(request: PodLogRequest): PodLogRequest {
        if (!request.containerName.isNullOrBlank()) {
            return request
        }
        val host = request.apiServer.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        if (isLocalHost(host)) {
            return request
        }
        val defaultContainer = runCatching { fetchDefaultContainerName(request) }.getOrNull()
        return if (defaultContainer.isNullOrBlank()) {
            request
        } else {
            request.copy(containerName = defaultContainer)
        }
    }

    private suspend fun fetchDefaultContainerName(request: PodLogRequest): String? = withContext(ioDispatcher) {
        val baseUrl = request.apiServer.toHttpUrlOrNull() ?: return@withContext null
        val basePathPrefix = baseUrl.encodedPath.let { path ->
            if (path == "/") "" else path.removeSuffix("/")
        }
        val podPath = "$basePathPrefix/api/v1/namespaces/${request.namespace}/pods/${request.podName}"
        val podUrl = baseUrl.newBuilder()
            .encodedPath(podPath)
            .build()
        val podRequest = Request.Builder()
            .url(podUrl)
            .get()
            .header("Accept", "application/json")
            .apply {
                request.bearerToken?.let { token ->
                    header("Authorization", "Bearer $token")
                }
            }
            .build()

        resolveTransportClient(request)
            .newBuilder()
            .callTimeout(250, TimeUnit.MILLISECONDS)
            .build()
            .newCall(podRequest)
            .execute()
            .use { response ->
            if (!response.isSuccessful) {
                return@withContext null
            }
            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isBlank()) {
                return@withContext null
            }
            val root = runCatching { JsonParser.parseString(responseBody).asJsonObject }.getOrNull()
                ?: return@withContext null
            val specElement = root.get("spec") ?: return@withContext null
            if (!specElement.isJsonObject) return@withContext null
            val containersElement = specElement.asJsonObject.get("containers") ?: return@withContext null
            if (!containersElement.isJsonArray) return@withContext null
            val containers = containersElement.asJsonArray
            containers
                .firstOrNull()
                ?.asJsonObject
                ?.get("name")
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun splitTimestamp(rawLine: String): Pair<String?, String> {
        val firstSpace = rawLine.indexOf(' ')
        if (firstSpace <= 0) {
            return null to rawLine
        }

        val maybeTimestamp = rawLine.substring(0, firstSpace)
        return if (RFC3339_PREFIX.matcher(maybeTimestamp).matches()) {
            maybeTimestamp to rawLine.substring(firstSpace + 1)
        } else {
            null to rawLine
        }
    }

    private fun mapError(throwable: Throwable, response: Response?): LogStreamError {
        val status = response?.code
        if (status != null) {
            return when (status) {
                401 -> LogStreamError.Unauthorized
                403 -> LogStreamError.Forbidden
                404 -> LogStreamError.NotFound
                408 -> LogStreamError.Timeout
                500 -> LogStreamError.ServerError
                else -> LogStreamError.Unknown("HTTP $status")
            }
        }

        return when (throwable) {
            is SSLException -> LogStreamError.TlsError(throwable.message)
            is EOFException -> LogStreamError.EofError(throwable.message)
            is InterruptedIOException -> LogStreamError.Timeout
            is SocketException, is UnknownHostException, is ConnectException -> LogStreamError.NetworkInterrupted
            else -> {
                val detail = throwable.message
                if (detail?.contains("not found", ignoreCase = true) == true || detail?.contains("404") == true) {
                    LogStreamError.NotFound
                } else {
                    LogStreamError.Unknown(detail)
                }
            }
        }
    }

    private fun Byte.toSource(): LogLine.Source {
        return when (toInt()) {
            2 -> LogLine.Source.STDERR
            1 -> LogLine.Source.STDOUT
            else -> LogLine.Source.UNKNOWN
        }
    }

    private fun detectSourceFromLinePrefix(
        message: String,
        defaultSource: LogLine.Source,
    ): Pair<LogLine.Source, String> {
        val trimmed = message.trimStart()
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("stderr:") -> LogLine.Source.STDERR to trimmed.removePrefix("stderr:").trimStart()
            lower.startsWith("stderr ") -> LogLine.Source.STDERR to trimmed.removePrefix("stderr").trimStart()
            lower.startsWith("[stderr]") -> LogLine.Source.STDERR to trimmed.removePrefix("[stderr]").trimStart()
            else -> defaultSource to message
        }
    }

    companion object {
        private fun normalizeServer(raw: String?): String? {
            val url = raw?.toHttpUrlOrNull() ?: return null
            return "${url.scheme}://${url.host.lowercase()}:${url.port}${url.encodedPath.removeSuffix("/")}"
        }

        private fun isLocalHost(host: String): Boolean {
            return host == "localhost" || host == "127.0.0.1" || host == "::1"
        }

        private val RFC3339_PREFIX: Pattern = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$",
        )
    }
}

/**
 * Exception wrapper used to propagate [LogStreamError] through [Result.failure].
 */
class LogStreamException(val error: LogStreamError) : Exception(error.toString())

typealias PodLogStreamRepository = LogStreamRepositoryImpl
