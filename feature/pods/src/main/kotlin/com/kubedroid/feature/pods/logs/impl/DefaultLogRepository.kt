package com.kubedroid.feature.pods.logs.impl

import com.kubedroid.core.network.logs.LogLine as NetworkLogLine
import com.kubedroid.core.network.logs.LogRepository as NetworkLogRepository
import com.kubedroid.core.network.logs.LogStreamError as NetworkLogStreamError
import com.kubedroid.core.network.logs.LogStreamException
import com.kubedroid.core.network.logs.PodLogRequest as NetworkPodLogRequest
import com.kubedroid.feature.pods.logs.LogLine
import com.kubedroid.feature.pods.logs.LogRepository
import com.kubedroid.feature.pods.logs.LogStreamError
import com.kubedroid.feature.pods.logs.PodLogRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DefaultLogRepository @Inject constructor(
    private val networkRepository: NetworkLogRepository,
) : LogRepository {

    override fun streamPodLogs(request: PodLogRequest): Flow<Result<LogLine>> {
        return networkRepository.streamPodLogs(request.toNetwork()).map { result ->
            result.fold(
                onSuccess = { line -> Result.success(line.toFeature()) },
                onFailure = { throwable ->
                    val mapped = (throwable as? LogStreamException)?.error?.toFeature()
                        ?: LogStreamError.Unknown(throwable.message)
                    Result.failure(FeatureLogStreamException(mapped))
                },
            )
        }
    }

    override suspend fun stopStream(streamId: String): Result<Unit> = networkRepository.stopStream(streamId)

    private fun PodLogRequest.toNetwork(): NetworkPodLogRequest {
        return NetworkPodLogRequest(
            streamId = streamId,
            apiServer = apiServer,
            namespace = namespace,
            podName = podName,
            containerName = containerName,
            bearerToken = bearerToken,
            tailLines = tailLines,
            sinceSeconds = sinceSeconds,
            follow = follow,
        )
    }

    private fun NetworkLogLine.toFeature(): LogLine {
        return LogLine(
            streamId = streamId,
            timestamp = timestamp,
            message = message,
            source = when (source) {
                NetworkLogLine.Source.STDOUT -> LogLine.Source.STDOUT
                NetworkLogLine.Source.STDERR -> LogLine.Source.STDERR
                NetworkLogLine.Source.UNKNOWN -> LogLine.Source.UNKNOWN
            },
        )
    }

    private fun NetworkLogStreamError.toFeature(): LogStreamError {
        return when (this) {
            NetworkLogStreamError.Unauthorized -> LogStreamError.Unauthorized
            NetworkLogStreamError.Forbidden -> LogStreamError.Forbidden
            NetworkLogStreamError.NotFound -> LogStreamError.NotFound
            NetworkLogStreamError.Timeout -> LogStreamError.Timeout
            NetworkLogStreamError.NetworkInterrupted -> LogStreamError.NetworkInterrupted
            NetworkLogStreamError.ServerError -> LogStreamError.ServerError
            is NetworkLogStreamError.TlsError -> LogStreamError.TlsError(detail)
            is NetworkLogStreamError.EofError -> LogStreamError.EofError(detail)
            is NetworkLogStreamError.Unknown -> LogStreamError.Unknown(detail)
        }
    }
}

class FeatureLogStreamException(
    val error: LogStreamError,
) : Exception(error.toString())
