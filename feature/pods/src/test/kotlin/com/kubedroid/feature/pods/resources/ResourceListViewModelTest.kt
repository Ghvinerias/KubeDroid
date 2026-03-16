package com.kubedroid.feature.pods.resources

import com.kubedroid.core.database.cache.StaleDataIndicator
import com.kubedroid.feature.metrics.domain.model.MetricPoint
import com.kubedroid.feature.metrics.domain.model.ResourceMetrics
import com.kubedroid.feature.metrics.domain.repository.MetricsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
class ResourceListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun load_success_updatesSuccessState() = runTest(dispatcher) {
        val repository = FakeResourceRepository(
            namespacesResult = Result.success(listOf("default", "kube-system")),
            resourcesByNamespace = mapOf(
                "default" to Result.success(
                    ResourceListData(
                        items = listOf(
                            ResourceListItem(
                                id = "pod/default/api",
                                name = "api",
                                namespace = "default",
                                kind = "Pod",
                                status = ResourceStatus.HEALTHY,
                            ),
                        ),
                        staleDataIndicator = StaleDataIndicator(
                            isFresh = true,
                            lastFetchedAt = 123L,
                            source = StaleDataIndicator.Source.Live,
                        ),
                    ),
                ),
            ),
        )

        val viewModel = ResourceListViewModel(repository, FakeMetricsRepository())

        viewModel.onIntent(ResourceListIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("default", "kube-system"), state.namespaces)
        assertTrue(state.listState is ResourceListUiState.Success)
        assertEquals(1, (state.listState as ResourceListUiState.Success).items.size)
    }

    @Test
    fun load_empty_updatesEmptyState() = runTest(dispatcher) {
        val repository = FakeResourceRepository(
            namespacesResult = Result.success(listOf("default")),
            resourcesByNamespace = mapOf(
                "default" to Result.success(
                    ResourceListData(
                        items = emptyList(),
                        staleDataIndicator = StaleDataIndicator(
                            isFresh = true,
                            lastFetchedAt = 123L,
                            source = StaleDataIndicator.Source.Live,
                        ),
                    ),
                ),
            ),
        )

        val viewModel = ResourceListViewModel(repository, FakeMetricsRepository())

        viewModel.onIntent(ResourceListIntent.Load)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.listState is ResourceListUiState.Empty)
    }

    @Test
    fun selectNamespace_failure_updatesErrorState() = runTest(dispatcher) {
        val repository = FakeResourceRepository(
            namespacesResult = Result.success(listOf("default", "prod")),
            resourcesByNamespace = mapOf(
                "default" to Result.success(
                    ResourceListData(
                        items = emptyList(),
                        staleDataIndicator = StaleDataIndicator(
                            isFresh = true,
                            lastFetchedAt = 123L,
                            source = StaleDataIndicator.Source.Live,
                        ),
                    ),
                ),
                "prod" to Result.failure(IllegalStateException("Forbidden")),
            ),
        )

        val viewModel = ResourceListViewModel(repository, FakeMetricsRepository())

        viewModel.onIntent(ResourceListIntent.SelectNamespace("prod"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("prod", state.selectedNamespace)
        assertTrue(state.listState is ResourceListUiState.Error)
        assertEquals("Forbidden", (state.listState as ResourceListUiState.Error).cause?.message)
    }
}

private class FakeResourceRepository(
    private val namespacesResult: Result<List<String>>,
    private val resourcesByNamespace: Map<String?, Result<ResourceListData>>,
) : ResourceRepository {

    override suspend fun listNamespaces(): Result<List<String>> = namespacesResult

    override suspend fun listResources(namespace: String?): Result<ResourceListData> {
        return resourcesByNamespace[namespace] ?: Result.success(
            ResourceListData(
                items = emptyList(),
                staleDataIndicator = StaleDataIndicator(
                    isFresh = true,
                    lastFetchedAt = null,
                    source = StaleDataIndicator.Source.Live,
                ),
            ),
        )
    }
}

private class FakeMetricsRepository : MetricsRepository {
    override suspend fun getPodMetrics(name: String, namespace: String): ResourceMetrics? = null

    override suspend fun getNodeMetrics(nodeName: String): MetricPoint? = null

    override fun watchPodMetrics(namespace: String): Flow<List<ResourceMetrics>> = flowOf(emptyList())

    override suspend fun isMetricsServerAvailable(): Boolean = false
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
