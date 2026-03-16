package com.kubedroid.core.network.logs

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString

class PodLogStreamRepositoryTest {

    @Test
    fun test_streamPodLogs_webSocketMessage_emitsParsedLogLine() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("2026-03-06T12:00:00Z pod started")
                    }
                },
            ),
        )
        server.start()

        val repository = LogStreamRepositoryImpl(OkHttpClient())
        val request = sampleRequest(apiServer = server.url("/").toString())

        val emission = withTimeout(3_000) { repository.streamPodLogs(request).first { it.isSuccess } }

        assertTrue(emission.isSuccess)
        val line = emission.getOrThrow()
        assertEquals(request.streamId, line.streamId)
        assertEquals("2026-03-06T12:00:00Z", line.timestamp)
        assertEquals("pod started", line.message)
        assertEquals(LogLine.Source.STDOUT, line.source)
        assertEquals(false, line.isError)

        server.shutdown()
    }

    @Test
    fun test_streamPodLogs_requestContainsExpectedPathQueryAndAuthorization() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("2026-03-06T12:00:00Z ready")
                    }
                },
            ),
        )
        server.start()

        val repository = LogStreamRepositoryImpl(OkHttpClient())
        val request = sampleRequest(
            apiServer = server.url("/").toString(),
            containerName = "main",
            bearerToken = "token-123",
            tailLines = 200,
            sinceSeconds = 60L,
            follow = false,
        )

        withTimeout(3_000) { repository.streamPodLogs(request).first { it.isSuccess } }

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals(
            "/api/v1/namespaces/default/pods/demo/log?follow=false&timestamps=true&container=main&tailLines=200&sinceSeconds=60",
            recorded.requestUrl?.encodedPath + "?" + recorded.requestUrl?.encodedQuery,
        )
        assertEquals("Bearer token-123", recorded.getHeader("Authorization"))

        server.shutdown()
    }

    @Test
    fun test_streamPodLogs_binaryStderrFrame_emitsErrorLine() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val payload = byteArrayOf(2) + "2026-03-06T12:00:00Z stderr line".encodeToByteArray()
                        webSocket.send(payload.toByteString())
                    }
                },
            ),
        )
        server.start()

        val repository = LogStreamRepositoryImpl(OkHttpClient())
        val request = sampleRequest(apiServer = server.url("/").toString())

        val emission = withTimeout(3_000) { repository.streamPodLogs(request).first { it.isSuccess } }
        val line = emission.getOrThrow()
        assertEquals("2026-03-06T12:00:00Z", line.timestamp)
        assertEquals("line", line.message)
        assertEquals(LogLine.Source.STDERR, line.source)
        assertTrue(line.isError)

        server.shutdown()
    }

    @Test
    fun test_streamPodLogs_textPrefixMarksStdErrAndTrimsPrefix() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("2026-03-06T12:00:00Z stderr: boom")
                    }
                },
            ),
        )
        server.start()

        val repository = LogStreamRepositoryImpl(OkHttpClient())
        val request = sampleRequest(apiServer = server.url("/").toString())

        val emission = withTimeout(3_000) { repository.streamPodLogs(request).first { it.isSuccess } }
        val line = emission.getOrThrow()

        assertEquals(LogLine.Source.STDERR, line.source)
        assertEquals("boom", line.message)
        assertTrue(line.isError)

        server.shutdown()
    }

    @Test
    fun test_streamPodLogs_skipsBlankLinesAndSplitsMultiLinePayload() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("\n2026-03-06T12:00:00Z line-a\n\n2026-03-06T12:00:01Z line-b\n")
                    }
                },
            ),
        )
        server.start()

        val repository = LogStreamRepositoryImpl(OkHttpClient())
        val request = sampleRequest(apiServer = server.url("/").toString())

        val emissions = withTimeout(3_000) {
            repository.streamPodLogs(request)
                .map { it.getOrThrow() }
                .firstOrNull { it.message == "line-b" }
        }

        assertEquals("line-b", emissions?.message)

        server.shutdown()
    }

    @Test
    fun test_buildWebSocketRequest_invalidApiServer_returnsNull() {
        val repository = LogStreamRepositoryImpl(OkHttpClient())
        val method = repository::class.java.getDeclaredMethod("buildWebSocketRequest", PodLogRequest::class.java)
        method.isAccessible = true

        val request = method.invoke(repository, sampleRequest(apiServer = "not-a-valid-url"))

        assertEquals(null, request)
    }

    @Test
    fun test_stopStream_activeStream_returnsSuccess() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()

        val repository = LogStreamRepositoryImpl(OkHttpClient())
        val request = sampleRequest(apiServer = server.url("/").toString())

        val collectJob = async {
            withTimeout(3_000) {
                repository.streamPodLogs(request).first()
            }
        }

        val stopResult = repository.stopStream(request.streamId)
        assertTrue(stopResult.isSuccess)

        collectJob.cancel()
        server.shutdown()
    }

    @Test
    fun test_streamLogs_mapsSuccessfulResultToRawLogLine() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("2026-03-06T12:00:00Z stream ready")
                    }
                },
            ),
        )
        server.start()

        val repository = LogStreamRepositoryImpl(OkHttpClient())
        val line = withTimeout(3_000) {
            repository.streamLogs(
                LogStreamConfig(
                    streamId = "stream-a",
                    apiServer = server.url("/").toString(),
                    namespace = "default",
                    podName = "demo",
                ),
            ).first()
        }

        assertEquals("stream ready", line.message)
        server.shutdown()
    }

    @Test
    fun test_streamPodLogs_mapError_http401_unauthorized() {
        assertIs<LogStreamError.Unauthorized>(
            invokeMapErrorForTest(throwable = RuntimeException("boom"), statusCode = 401),
        )
    }

    @Test
    fun test_streamPodLogs_mapError_http403_forbidden() {
        assertIs<LogStreamError.Forbidden>(
            invokeMapErrorForTest(throwable = RuntimeException("boom"), statusCode = 403),
        )
    }

    @Test
    fun test_streamPodLogs_mapError_http404_notFound() {
        assertIs<LogStreamError.NotFound>(
            invokeMapErrorForTest(throwable = RuntimeException("boom"), statusCode = 404),
        )
    }

    @Test
    fun test_streamPodLogs_mapError_http408_timeout() {
        assertIs<LogStreamError.Timeout>(
            invokeMapErrorForTest(throwable = RuntimeException("boom"), statusCode = 408),
        )
    }

    @Test
    fun test_streamPodLogs_mapError_http500_serverError() {
        assertIs<LogStreamError.ServerError>(
            invokeMapErrorForTest(throwable = RuntimeException("boom"), statusCode = 500),
        )
    }

    @Test
    fun test_streamPodLogs_mapError_httpUnknownStatus_mapsUnknownWithCode() {
        val error = invokeMapErrorForTest(throwable = RuntimeException("boom"), statusCode = 418)

        assertIs<LogStreamError.Unknown>(error)
        assertEquals("HTTP 418", error.detail)
    }

    @Test
    fun test_streamPodLogs_mapError_tls_mapsTlsError() {
        assertIs<LogStreamError.TlsError>(
            invokeMapErrorForTest(throwable = SSLException("tls"), statusCode = null),
        )
    }

    @Test
    fun test_streamPodLogs_mapError_eof_mapsEofError() {
        assertIs<LogStreamError.EofError>(
            invokeMapErrorForTest(throwable = EOFException("eof"), statusCode = null),
        )
    }

    @Test
    fun test_streamPodLogs_mapError_interruptedIo_mapsTimeout() {
        assertIs<LogStreamError.Timeout>(
            invokeMapErrorForTest(throwable = InterruptedIOException("timeout"), statusCode = null),
        )
    }

    @Test
    fun test_streamPodLogs_mapError_socketException_mapsNetworkInterrupted() {
        assertIs<LogStreamError.NetworkInterrupted>(
            invokeMapErrorForTest(throwable = SocketException("reset"), statusCode = null),
        )
    }

    @Test
    fun test_streamPodLogs_mapError_unknownHost_mapsNetworkInterrupted() {
        assertIs<LogStreamError.NetworkInterrupted>(
            invokeMapErrorForTest(throwable = UnknownHostException("dns"), statusCode = null),
        )
    }

    @Test
    fun test_streamPodLogs_mapError_messageNotFound_mapsNotFound() {
        assertIs<LogStreamError.NotFound>(
            invokeMapErrorForTest(throwable = RuntimeException("pod not found"), statusCode = null),
        )
    }

    private fun invokeMapErrorForTest(throwable: Throwable, statusCode: Int?): LogStreamError {
        val repository = LogStreamRepositoryImpl(OkHttpClient())
        val response = statusCode?.let {
            Response.Builder()
                .request(Request.Builder().url("https://example.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(it)
                .message("x")
                .build()
        }

        val method = repository::class.java.getDeclaredMethod(
            "mapError",
            Throwable::class.java,
            Response::class.java,
        )
        method.isAccessible = true
        return method.invoke(repository, throwable, response) as LogStreamError
    }

    private fun sampleRequest(
        apiServer: String,
        containerName: String? = null,
        bearerToken: String? = null,
        tailLines: Int? = null,
        sinceSeconds: Long? = null,
        follow: Boolean = true,
    ): PodLogRequest = PodLogRequest(
        streamId = "stream-a",
        apiServer = apiServer,
        namespace = "default",
        podName = "demo",
        containerName = containerName,
        bearerToken = bearerToken,
        tailLines = tailLines,
        sinceSeconds = sinceSeconds,
        follow = follow,
    )
}
