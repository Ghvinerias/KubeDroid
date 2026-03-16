package com.kubedroid.feature.settings.ui

import com.kubedroid.core.network.connection.ClusterConnectionRepository
import com.kubedroid.core.network.connection.ClusterConnectionState
import com.kubedroid.core.network.kubeconfig.KubeConfig
import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import com.kubedroid.core.network.kubeconfig.KubeContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class ClusterSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun saveCluster_manualMode_defaultsInsecureSkipTlsVerifyToFalse() = runTest {
        val kubeConfigRepository = FakeKubeConfigRepository()
        val viewModel = ClusterSettingsViewModel(
            kubeConfigRepository = kubeConfigRepository,
            clusterConnectionRepository = FakeClusterConnectionRepository(),
        )
        advanceUntilIdle()

        viewModel.onInputModeChange(AddClusterInputMode.MANUAL)
        viewModel.onServerChange("https://cluster.example.com")
        viewModel.onTokenChange("token-123")
        viewModel.saveCluster(reconnect = false, onComplete = {})
        advanceUntilIdle()

        val saved = kubeConfigRepository.lastSavedConfig ?: error("Expected config to be saved")
        val insecure = saved.clusters.last().raw["insecure-skip-tls-verify"] as? Boolean
        assertEquals(false, insecure)
    }

    @Test
    fun saveCluster_manualMode_setsInsecureSkipTlsVerifyTrue_onlyAfterConfirmation() = runTest {
        val kubeConfigRepository = FakeKubeConfigRepository()
        val viewModel = ClusterSettingsViewModel(
            kubeConfigRepository = kubeConfigRepository,
            clusterConnectionRepository = FakeClusterConnectionRepository(),
        )
        advanceUntilIdle()

        viewModel.onInputModeChange(AddClusterInputMode.MANUAL)
        viewModel.onServerChange("https://cluster.example.com")
        viewModel.onTokenChange("token-123")

        viewModel.onInsecureTlsToggleRequested(true)
        assertTrue(viewModel.uiState.value.showInsecureTlsWarningDialog)
        assertFalse(viewModel.uiState.value.insecureTlsEnabled)

        viewModel.saveCluster(reconnect = false, onComplete = {})
        advanceUntilIdle()
        var saved = kubeConfigRepository.lastSavedConfig ?: error("Expected config to be saved")
        var insecure = saved.clusters.last().raw["insecure-skip-tls-verify"] as? Boolean
        assertEquals(false, insecure)

        viewModel.confirmEnableInsecureTls()
        assertTrue(viewModel.uiState.value.insecureTlsEnabled)
        assertFalse(viewModel.uiState.value.showInsecureTlsWarningDialog)

        viewModel.onInputModeChange(AddClusterInputMode.MANUAL)
        viewModel.onServerChange("https://cluster.example.com")
        viewModel.onTokenChange("token-123")
        viewModel.saveCluster(reconnect = false, onComplete = {})
        advanceUntilIdle()
        saved = kubeConfigRepository.lastSavedConfig ?: error("Expected config to be saved")
        insecure = saved.clusters.last().raw["insecure-skip-tls-verify"] as? Boolean
        assertEquals(true, insecure)
    }

    @Test
    fun insecureTlsToggleState_isPreservedInViewModel() = runTest {
        val viewModel = ClusterSettingsViewModel(
            kubeConfigRepository = FakeKubeConfigRepository(),
            clusterConnectionRepository = FakeClusterConnectionRepository(),
        )
        advanceUntilIdle()

        viewModel.onInputModeChange(AddClusterInputMode.MANUAL)
        viewModel.onInsecureTlsToggleRequested(true)
        assertTrue(viewModel.uiState.value.showInsecureTlsWarningDialog)
        assertFalse(viewModel.uiState.value.insecureTlsEnabled)

        viewModel.confirmEnableInsecureTls()
        viewModel.onServerChange("https://cluster.example.com")
        viewModel.onTokenChange("token-123")

        assertTrue(viewModel.uiState.value.insecureTlsEnabled)
        assertEquals("https://cluster.example.com", viewModel.uiState.value.server)
        assertEquals("token-123", viewModel.uiState.value.token)

        viewModel.onInsecureTlsToggleRequested(false)
        assertFalse(viewModel.uiState.value.insecureTlsEnabled)
    }
}

private class FakeKubeConfigRepository(
    initial: KubeConfig = KubeConfig(
        contexts = emptyList(),
        clusters = emptyList(),
        users = emptyList(),
        currentContext = null,
    ),
) : KubeConfigRepository {
    private var current: KubeConfig = initial
    var lastSavedConfig: KubeConfig? = null
        private set

    override suspend fun load(): Result<KubeConfig> = Result.success(current)

    override suspend fun save(config: KubeConfig): Result<Unit> {
        current = config
        lastSavedConfig = config
        return Result.success(Unit)
    }

    override suspend fun delete(): Result<Unit> = Result.success(Unit)

    override suspend fun listContexts(): Result<List<KubeContext>> = Result.success(current.contexts)

    override suspend fun setActiveContext(contextName: String): Result<Unit> = Result.success(Unit)
}

private class FakeClusterConnectionRepository : ClusterConnectionRepository {
    private val state = MutableStateFlow<ClusterConnectionState>(ClusterConnectionState.Disconnected)

    override suspend fun connect(context: KubeContext): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect(): Result<Unit> = Result.success(Unit)

    override fun getState(): Flow<ClusterConnectionState> = state.asStateFlow()
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
