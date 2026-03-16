package com.kubedroid.feature.pods.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class LogRepositoryTest {

    @Test
    fun test_LogLine_dataClass_preservesValues() {
        val line = LogLine(
            streamId = "stream-1",
            timestamp = "2026-01-01T00:00:00Z",
            message = "ready",
            source = LogLine.Source.STDOUT,
        )

        assertEquals("stream-1", line.streamId)
        assertEquals("2026-01-01T00:00:00Z", line.timestamp)
        assertEquals("ready", line.message)
        assertEquals(LogLine.Source.STDOUT, line.source)
    }

    @Test
    fun test_PodLogRequest_defaults_areApplied() {
        val request = PodLogRequest(
            streamId = "stream-1",
            apiServer = "https://cluster.local",
            namespace = "default",
            podName = "demo-pod",
        )

        assertNull(request.containerName)
        assertNull(request.bearerToken)
        assertNull(request.tailLines)
        assertNull(request.sinceSeconds)
        assertTrue(request.follow)
    }

    @Test
    fun test_streamPodLogs_happyPath_emitsSuccess() = runTest {
        val repository = FakeLogRepository(
            stream = flowOf(Result.success(LogLine("s", null, "line", LogLine.Source.STDOUT))),
        )

        val result = repository.streamPodLogs(sampleRequest()).first()

        assertTrue(result.isSuccess)
        assertEquals("line", result.getOrThrow().message)
    }

    @Test
    fun test_streamPodLogs_errorPath_emitsFailureWithTypedError() = runTest {
        val repository = FakeLogRepository(
            stream = flowOf(Result.failure(LogStreamFailure(LogStreamError.NotFound))),
        )

        val result = repository.streamPodLogs(sampleRequest()).first()

        assertTrue(result.isFailure)
        val failure = assertIs<LogStreamFailure>(result.exceptionOrNull())
        assertIs<LogStreamError.NotFound>(failure.error)
    }

    @Test
    fun test_stopStream_happyPath_returnsSuccess() = runTest {
        val repository = FakeLogRepository(
            stream = flowOf(Result.success(LogLine("s", null, "line", LogLine.Source.STDOUT))),
            stopResult = Result.success(Unit),
        )

        val result = repository.stopStream("stream-1")

        assertTrue(result.isSuccess)
    }

    @Test
    fun test_stopStream_errorPath_returnsFailureWithTypedError() = runTest {
        val repository = FakeLogRepository(
            stream = flowOf(Result.success(LogLine("s", null, "line", LogLine.Source.STDOUT))),
            stopResult = Result.failure(LogStreamFailure(LogStreamError.Forbidden)),
        )

        val result = repository.stopStream("missing")

        assertTrue(result.isFailure)
        val failure = assertIs<LogStreamFailure>(result.exceptionOrNull())
        assertIs<LogStreamError.Forbidden>(failure.error)
    }

    @Test
    fun test_LogStreamError_detailCarryingVariants_preserveDetail() {
        val tls = LogStreamError.TlsError("bad cert")
        val eof = LogStreamError.EofError("eof")
        val unknown = LogStreamError.Unknown("something else")

        assertEquals("bad cert", tls.detail)
        assertEquals("eof", eof.detail)
        assertEquals("something else", unknown.detail)
    }

    private fun sampleRequest(): PodLogRequest = PodLogRequest(
        streamId = "stream-1",
        apiServer = "https://cluster.local",
        namespace = "default",
        podName = "demo-pod",
    )
}

private class FakeLogRepository(
    private val stream: Flow<Result<LogLine>>,
    private val stopResult: Result<Unit> = Result.success(Unit),
) : LogRepository {
    override fun streamPodLogs(request: PodLogRequest): Flow<Result<LogLine>> = stream

    override suspend fun stopStream(streamId: String): Result<Unit> = stopResult
}

private class LogStreamFailure(val error: LogStreamError) : RuntimeException(error.toString())
