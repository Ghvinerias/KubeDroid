package com.kubedroid.core.network.logs

import kotlinx.coroutines.flow.Flow

/**
 * One rendered log line emitted by the pod log stream.
 */
data class LogLine(
    val streamId: String,
    val timestamp: String?,
    val message: String,
    val source: Source,
    val isError: Boolean = source == Source.STDERR,
) {
    enum class Source {
        STDOUT,
        STDERR,
        UNKNOWN,
    }
}

/**
 * Canonical config for starting a log stream.
 */
data class LogStreamConfig(
    val streamId: String,
    val apiServer: String,
    val namespace: String,
    val podName: String,
    val containerName: String? = null,
    val bearerToken: String? = null,
    val tailLines: Int? = null,
    val sinceSeconds: Long? = null,
    val follow: Boolean = true,
)

/**
 * Canonical contract for pod log streaming.
 */
interface LogStreamRepository {
    fun streamLogs(config: LogStreamConfig): Flow<LogLine>
    fun stopStream()
}

/**
 * Parameters required to open a pod log stream.
 */
data class PodLogRequest(
    val streamId: String,
    val apiServer: String,
    val namespace: String,
    val podName: String,
    val containerName: String? = null,
    val bearerToken: String? = null,
    val tailLines: Int? = null,
    val sinceSeconds: Long? = null,
    val follow: Boolean = true,
)

/**
 * Structured error states for pod log streaming.
 */
sealed interface LogStreamError {
    data object Unauthorized : LogStreamError
    data object Forbidden : LogStreamError
    data object NotFound : LogStreamError
    data object Timeout : LogStreamError
    data object NetworkInterrupted : LogStreamError
    data object ServerError : LogStreamError
    data class TlsError(val detail: String?) : LogStreamError
    data class EofError(val detail: String?) : LogStreamError
    data class Unknown(val detail: String?) : LogStreamError
}

/**
 * Contract for opening and controlling pod log streams.
 */
interface LogRepository {
    fun streamPodLogs(request: PodLogRequest): Flow<Result<LogLine>>
    suspend fun stopStream(streamId: String): Result<Unit>
}
