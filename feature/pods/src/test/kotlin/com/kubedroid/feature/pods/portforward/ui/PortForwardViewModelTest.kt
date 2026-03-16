package com.kubedroid.feature.pods.portforward.ui

import com.kubedroid.core.network.portforward.PortForwardSession
import com.kubedroid.core.network.portforward.PortForwardStatus
import com.kubedroid.feature.pods.portforward.PodPortForwardRequest
import com.kubedroid.feature.pods.portforward.PortForwardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class PortForwardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun startPortForward_success_addsActiveSession() = runTest(dispatcher) {
        val session = FakePortForwardSession(localPort = 18080)
        val viewModel = PortForwardViewModel(
            portForwardRepository = FakePortForwardRepository(Result.success(session)),
        )

        viewModel.onIntent(
            PortForwardIntent.Initialize(
                apiServer = "https://cluster.example.com",
                namespace = "default",
                bearerToken = "token",
                pods = listOf("api-123"),
            ),
        )
        viewModel.onIntent(PortForwardIntent.OpenStartSheet)
        viewModel.onIntent(PortForwardIntent.SelectPod("api-123"))
        viewModel.onIntent(PortForwardIntent.UpdateRemotePort("80"))
        viewModel.onIntent(PortForwardIntent.StartPortForward)

        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.sessions.size)
        assertEquals(PortForwardSessionUiStatus.ACTIVE, viewModel.uiState.value.sessions.first().status)
        assertFalse(viewModel.uiState.value.isStartSheetVisible)
    }

    @Test
    fun closeSession_closesAndRemovesSession() = runTest(dispatcher) {
        val session = FakePortForwardSession(localPort = 18080)
        val viewModel = PortForwardViewModel(
            portForwardRepository = FakePortForwardRepository(Result.success(session)),
        )

        viewModel.onIntent(
            PortForwardIntent.Initialize(
                apiServer = "https://cluster.example.com",
                namespace = "default",
                bearerToken = "token",
                pods = listOf("api-123"),
            ),
        )
        viewModel.onIntent(PortForwardIntent.OpenStartSheet)
        viewModel.onIntent(PortForwardIntent.SelectPod("api-123"))
        viewModel.onIntent(PortForwardIntent.UpdateRemotePort("80"))
        viewModel.onIntent(PortForwardIntent.StartPortForward)
        advanceUntilIdle()

        val sessionId = viewModel.uiState.value.sessions.first().id
        viewModel.onIntent(PortForwardIntent.CloseSession(sessionId))
        advanceUntilIdle()

        assertTrue(session.closed)
        assertTrue(viewModel.uiState.value.sessions.isEmpty())
    }

    @Test
    fun onCleared_closesAllSessions() = runTest(dispatcher) {
        val first = FakePortForwardSession(localPort = 18080)
        val second = FakePortForwardSession(localPort = 19090)
        val repository = SequencedPortForwardRepository(
            results = listOf(Result.success(first), Result.success(second)),
        )
        val viewModel = PortForwardViewModel(portForwardRepository = repository)

        viewModel.onIntent(
            PortForwardIntent.Initialize(
                apiServer = "https://cluster.example.com",
                namespace = "default",
                bearerToken = "token",
                pods = listOf("api-123", "api-456"),
            ),
        )

        viewModel.onIntent(PortForwardIntent.OpenStartSheet)
        viewModel.onIntent(PortForwardIntent.SelectPod("api-123"))
        viewModel.onIntent(PortForwardIntent.UpdateRemotePort("80"))
        viewModel.onIntent(PortForwardIntent.StartPortForward)

        viewModel.onIntent(PortForwardIntent.OpenStartSheet)
        viewModel.onIntent(PortForwardIntent.SelectPod("api-456"))
        viewModel.onIntent(PortForwardIntent.UpdateRemotePort("81"))
        viewModel.onIntent(PortForwardIntent.StartPortForward)

        advanceUntilIdle()
        invokeOnCleared(viewModel)

        assertTrue(first.closed)
        assertTrue(second.closed)
        assertTrue(viewModel.uiState.value.sessions.isEmpty())
    }

    private fun invokeOnCleared(viewModel: PortForwardViewModel) {
        val method = PortForwardViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(viewModel)
    }
}

private class FakePortForwardRepository(
    private val result: Result<PortForwardSession>,
) : PortForwardRepository {
    override suspend fun createSession(request: PodPortForwardRequest): Result<PortForwardSession> = result
}

private class SequencedPortForwardRepository(
    private val results: List<Result<PortForwardSession>>,
) : PortForwardRepository {
    private var index = 0

    override suspend fun createSession(request: PodPortForwardRequest): Result<PortForwardSession> {
        val next = results[index]
        index += 1
        return next
    }
}

private class FakePortForwardSession(
    override val localPort: Int,
) : PortForwardSession {
    private val _statusFlow = MutableStateFlow<PortForwardStatus>(PortForwardStatus.Active)
    override val statusFlow: StateFlow<PortForwardStatus> = _statusFlow

    var closed: Boolean = false

    override fun close() {
        closed = true
        _statusFlow.value = PortForwardStatus.Closed
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
