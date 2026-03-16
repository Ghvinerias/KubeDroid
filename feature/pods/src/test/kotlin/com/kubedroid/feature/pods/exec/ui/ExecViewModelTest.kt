package com.kubedroid.feature.pods.exec.ui

import com.kubedroid.feature.pods.exec.ExecRepository
import com.kubedroid.feature.pods.exec.PodExecRequest
import com.kubedroid.feature.pods.exec.PodExecSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class ExecViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun connect_success_setsActiveAndEmitsClear() = runTest(dispatcher) {
        val session = FakePodExecSession()
        val viewModel = ExecViewModel(
            execRepository = FakeExecRepository(Result.success(session)),
        )

        viewModel.onIntent(ExecIntent.Connect(sampleRequest()))
        advanceUntilIdle()

        assertEquals(ExecUiState.Active, viewModel.uiState.value)
        assertTrue(viewModel.terminalEvents.replayCache.first() is ExecTerminalEvent.Clear)
    }

    @Test
    fun connect_failure_setsErrorState() = runTest(dispatcher) {
        val viewModel = ExecViewModel(
            execRepository = FakeExecRepository(Result.failure(IllegalStateException("forbidden"))),
        )

        viewModel.onIntent(ExecIntent.Connect(sampleRequest()))
        advanceUntilIdle()

        assertEquals(ExecUiState.Error("forbidden"), viewModel.uiState.value)
    }

    @Test
    fun terminalFlows_forwardToTerminalEvents() = runTest(dispatcher) {
        val session = FakePodExecSession()
        val viewModel = ExecViewModel(
            execRepository = FakeExecRepository(Result.success(session)),
        )

        viewModel.onIntent(ExecIntent.Connect(sampleRequest()))
        advanceUntilIdle()

        session.emitStdout("hello")
        session.emitStderr("oops")
        advanceUntilIdle()

        assertTrue(viewModel.terminalEvents.replayCache.contains(ExecTerminalEvent.Stdout("hello")))
        assertTrue(viewModel.terminalEvents.replayCache.contains(ExecTerminalEvent.Stderr("oops")))
    }

    @Test
    fun exitCode_updatesExitedStateAndClosesSession() = runTest(dispatcher) {
        val session = FakePodExecSession()
        val viewModel = ExecViewModel(
            execRepository = FakeExecRepository(Result.success(session)),
        )

        viewModel.onIntent(ExecIntent.Connect(sampleRequest()))
        advanceUntilIdle()

        session.emitExitCode(42)
        advanceUntilIdle()

        assertEquals(ExecUiState.Exited(42), viewModel.uiState.value)
        assertTrue(session.isClosed)
    }

    @Test
    fun sendInput_delegatesToActiveSession() = runTest(dispatcher) {
        val session = FakePodExecSession()
        val viewModel = ExecViewModel(
            execRepository = FakeExecRepository(Result.success(session)),
        )

        viewModel.onIntent(ExecIntent.Connect(sampleRequest()))
        advanceUntilIdle()
        viewModel.onIntent(ExecIntent.SendInput("ls\n"))
        advanceUntilIdle()

        assertEquals(listOf("ls\n"), session.sentInputs)
    }

    @Test
    fun sendInput_failure_setsErrorState() = runTest(dispatcher) {
        val session = FakePodExecSession(sendInputFailure = IllegalStateException("broken pipe"))
        val viewModel = ExecViewModel(
            execRepository = FakeExecRepository(Result.success(session)),
        )

        viewModel.onIntent(ExecIntent.Connect(sampleRequest()))
        advanceUntilIdle()
        viewModel.onIntent(ExecIntent.SendInput("pwd\n"))
        advanceUntilIdle()

        assertEquals(ExecUiState.Error("broken pipe"), viewModel.uiState.value)
    }

    @Test
    fun resize_delegatesToActiveSession() = runTest(dispatcher) {
        val session = FakePodExecSession()
        val viewModel = ExecViewModel(
            execRepository = FakeExecRepository(Result.success(session)),
        )

        viewModel.onIntent(ExecIntent.Connect(sampleRequest()))
        advanceUntilIdle()
        viewModel.onIntent(ExecIntent.Resize(columns = 120, rows = 40))
        advanceUntilIdle()

        assertEquals(listOf(120 to 40), session.resizeCalls)
    }

    @Test
    fun disconnect_setsIdleAndClosesSession() = runTest(dispatcher) {
        val session = FakePodExecSession()
        val viewModel = ExecViewModel(
            execRepository = FakeExecRepository(Result.success(session)),
        )

        viewModel.onIntent(ExecIntent.Connect(sampleRequest()))
        advanceUntilIdle()
        viewModel.onIntent(ExecIntent.Disconnect)
        advanceUntilIdle()

        assertEquals(ExecUiState.Idle, viewModel.uiState.value)
        assertTrue(session.isClosed)
    }

    @Test
    fun sendInput_withoutActiveSession_isNoOp() = runTest(dispatcher) {
        val viewModel = ExecViewModel(
            execRepository = FakeExecRepository(Result.failure(IllegalStateException("unused"))),
        )

        viewModel.onIntent(ExecIntent.SendInput("echo hi\n"))
        advanceUntilIdle()

        assertEquals(ExecUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun resize_withoutActiveSession_isNoOp() = runTest(dispatcher) {
        val viewModel = ExecViewModel(
            execRepository = FakeExecRepository(Result.failure(IllegalStateException("unused"))),
        )

        viewModel.onIntent(ExecIntent.Resize(columns = 100, rows = 25))
        advanceUntilIdle()

        assertEquals(ExecUiState.Idle, viewModel.uiState.value)
    }

    private fun sampleRequest(): PodExecRequest {
        return PodExecRequest(
            apiServer = "https://cluster.example.com",
            namespace = "default",
            podName = "api-123",
            command = listOf("sh"),
            bearerToken = "token",
        )
    }
}

private class FakeExecRepository(
    private val result: Result<PodExecSession>,
) : ExecRepository {
    override suspend fun createSession(request: PodExecRequest): Result<PodExecSession> = result
}

private class FakePodExecSession(
    private val sendInputFailure: Throwable? = null,
) : PodExecSession {
    private val output = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val error = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val exitCode = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    override val outputFlow: SharedFlow<String> = output.asSharedFlow()
    override val errorFlow: SharedFlow<String> = error.asSharedFlow()
    override val exitCodeFlow: SharedFlow<Int> = exitCode.asSharedFlow()

    val sentInputs = mutableListOf<String>()
    val resizeCalls = mutableListOf<Pair<Int, Int>>()
    var isClosed: Boolean = false
        private set

    suspend fun emitStdout(value: String) {
        output.emit(value)
    }

    suspend fun emitStderr(value: String) {
        error.emit(value)
    }

    suspend fun emitExitCode(code: Int) {
        exitCode.emit(code)
    }

    override suspend fun sendInput(input: String) {
        sendInputFailure?.let { throw it }
        sentInputs += input
    }

    override suspend fun resize(columns: Int, rows: Int) {
        resizeCalls += columns to rows
    }

    override fun close() {
        isClosed = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
