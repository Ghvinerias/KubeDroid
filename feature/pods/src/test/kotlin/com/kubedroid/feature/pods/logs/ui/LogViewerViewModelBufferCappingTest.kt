package com.kubedroid.feature.pods.logs.ui

import com.kubedroid.feature.pods.logs.LogLine
import com.kubedroid.feature.pods.logs.LogRepository
import com.kubedroid.feature.pods.logs.PodLogRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerViewModelBufferCappingTest {

    @get:Rule
    val mainDispatcherRule = BufferMainDispatcherRule()

    @Test
    fun test_bufferCapping_keepsMostRecent10000Lines() = runTest {
        val repository = BufferFakeLogRepository(
            streamFlow = flow {
                repeat(10_050) { index ->
                    emit(
                        Result.success(
                            LogLine(
                                streamId = "container-a",
                                timestamp = null,
                                message = "line-$index",
                                source = LogLine.Source.STDOUT,
                            ),
                        ),
                    )
                }
            },
        )
        val viewModel = LogViewerViewModel(repository)

        viewModel.onIntent(LogViewerIntent.Start(sampleRequest()))
        advanceUntilIdle()

        assertEquals(10_000, viewModel.uiState.value.lines.size)
        assertEquals("line-50", viewModel.uiState.value.lines.first().message)
        assertEquals("line-10049", viewModel.uiState.value.lines.last().message)
    }

    @Test
    fun test_bufferCapping_blankLinesDoNotEvictExistingBuffer() = runTest {
        val repository = BufferFakeLogRepository(
            streamFlow = flow {
                repeat(10_000) { index ->
                    emit(
                        Result.success(
                            LogLine(
                                streamId = "container-a",
                                timestamp = null,
                                message = "line-$index",
                                source = LogLine.Source.STDOUT,
                            ),
                        ),
                    )
                }
                emit(
                    Result.success(
                        LogLine(
                            streamId = "container-a",
                            timestamp = null,
                            message = "   ",
                            source = LogLine.Source.STDOUT,
                        ),
                    ),
                )
            },
        )
        val viewModel = LogViewerViewModel(repository)

        viewModel.onIntent(LogViewerIntent.Start(sampleRequest()))
        advanceUntilIdle()

        assertEquals(10_000, viewModel.uiState.value.lines.size)
        assertEquals("line-0", viewModel.uiState.value.lines.first().message)
        assertEquals("line-9999", viewModel.uiState.value.lines.last().message)
        assertTrue(viewModel.uiState.value.lines.none { it.message.isBlank() })
    }

    private fun sampleRequest(): PodLogRequest = PodLogRequest(
        streamId = "stream-1",
        apiServer = "https://cluster.local",
        namespace = "default",
        podName = "demo-pod",
    )
}

private class BufferFakeLogRepository(
    private val streamFlow: Flow<Result<LogLine>>,
) : LogRepository {

    override fun streamPodLogs(request: PodLogRequest): Flow<Result<LogLine>> = streamFlow

    override suspend fun stopStream(streamId: String): Result<Unit> = Result.success(Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
class BufferMainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
