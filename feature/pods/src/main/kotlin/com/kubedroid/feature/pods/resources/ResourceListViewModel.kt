package com.kubedroid.feature.pods.resources

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.database.cache.StaleDataIndicator
import com.kubedroid.core.network.namespace.NamespaceStore
import com.kubedroid.feature.metrics.domain.model.ResourceMetrics
import com.kubedroid.feature.metrics.domain.repository.MetricsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_NAMESPACE = "default"

data class ResourceListScreenState(
    val selectedNamespace: String = DEFAULT_NAMESPACE,
    val namespaces: List<String> = emptyList(),
    val listState: ResourceListUiState<ResourceListItem> = ResourceListUiState.Loading,
    val isDataFromCache: Boolean = false,
    val lastUpdatedAtEpochMillis: Long? = null,
    val isNamespaceSwitcherVisible: Boolean = false,
)

sealed interface ResourceListIntent {
    data object Load : ResourceListIntent
    data object Retry : ResourceListIntent
    data object OpenNamespaceSwitcher : ResourceListIntent
    data object DismissNamespaceSwitcher : ResourceListIntent
    data class SelectNamespace(val namespace: String?) : ResourceListIntent
}

@HiltViewModel
class ResourceListViewModel @Inject constructor(
    private val repository: ResourceRepository,
    private val metricsRepository: MetricsRepository,
    private val namespaceStore: NamespaceStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ResourceListScreenState())
    val uiState: StateFlow<ResourceListScreenState> = _uiState.asStateFlow()

    init {
        observeSelectedNamespace()
    }

    fun onIntent(intent: ResourceListIntent) {
        when (intent) {
            ResourceListIntent.Load -> loadResources(currentNamespace())
            ResourceListIntent.Retry -> loadResources(currentNamespace())
            ResourceListIntent.OpenNamespaceSwitcher -> {
                _uiState.update { it.copy(isNamespaceSwitcherVisible = true) }
            }

            ResourceListIntent.DismissNamespaceSwitcher -> {
                _uiState.update { it.copy(isNamespaceSwitcherVisible = false) }
            }

            is ResourceListIntent.SelectNamespace -> {
                _uiState.update {
                    it.copy(
                        isNamespaceSwitcherVisible = false,
                    )
                }
                val selectedNamespace = intent.namespace
                    ?.trim()
                    ?.ifEmpty { DEFAULT_NAMESPACE }
                    ?: DEFAULT_NAMESPACE
                viewModelScope.launch {
                    namespaceStore.setNamespace(selectedNamespace)
                }
            }
        }
    }

    private fun currentNamespace(): String = _uiState.value.selectedNamespace

    private fun loadResources(namespace: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    listState = ResourceListUiState.Loading,
                    isDataFromCache = false,
                    lastUpdatedAtEpochMillis = null,
                )
            }

            val namespacesResult = repository.listNamespaces()
            val resourcesResult = repository.listResources(namespace)

            namespacesResult.onSuccess { namespaces ->
                _uiState.update { it.copy(namespaces = namespaces) }
            }

            resourcesResult.fold(
                onSuccess = { data ->
                    val resourcesWithMetrics = enrichResourcesWithMetrics(data.items)
                    _uiState.update {
                        it.copy(
                            listState = if (resourcesWithMetrics.isEmpty()) {
                                ResourceListUiState.Empty
                            } else {
                                ResourceListUiState.Success(resourcesWithMetrics)
                            },
                            isDataFromCache = data.staleDataIndicator.source == StaleDataIndicator.Source.Cache,
                            lastUpdatedAtEpochMillis = data.staleDataIndicator.lastFetchedAt,
                        )
                    }
                },
                onFailure = { throwable ->
                    val text = throwable.localizedMessage ?: throwable.message ?: "Unknown error"
                    runCatching { Log.e(TAG, "Resource list failed: $text", throwable) }
                    _uiState.update {
                        it.copy(
                            listState = ResourceListUiState.Error(throwable),
                            isDataFromCache = false,
                            lastUpdatedAtEpochMillis = null,
                        )
                    }
                },
            )
        }
    }

    private fun observeSelectedNamespace() {
        viewModelScope.launch {
            namespaceStore.selectedNamespace
                .distinctUntilChanged()
                .collect { namespace ->
                    _uiState.update { state ->
                        state.copy(selectedNamespace = namespace)
                    }
                    loadResources(namespace)
                }
        }
    }

    private suspend fun enrichResourcesWithMetrics(
        resources: List<ResourceListItem>,
    ): List<ResourceListItem> {
        if (resources.isEmpty()) return resources
        if (!runCatching { metricsRepository.isMetricsServerAvailable() }.getOrDefault(false)) {
            return resources
        }

        val podItemsByNamespace = resources
            .asSequence()
            .filter { it.kind.equals(POD_KIND, ignoreCase = true) }
            .groupBy { it.namespace }

        if (podItemsByNamespace.isEmpty()) return resources

        val metricsByPodKey = mutableMapOf<PodResourceKey, ResourceMetrics>()
        podItemsByNamespace.forEach { (namespace, _) ->
            val namespaceMetrics = runCatching { metricsRepository.watchPodMetrics(namespace).first() }
                .getOrDefault(emptyList())
            namespaceMetrics.forEach { metric ->
                metricsByPodKey[PodResourceKey(namespace = metric.namespace, podName = metric.podName)] = metric
            }
        }

        return resources.map { item ->
            val metric = metricsByPodKey[PodResourceKey(namespace = item.namespace, podName = item.name)] ?: return@map item
            val pointsByTimestamp = mutableMapOf<Long, AggregatedPoint>()
            metric.containers.forEach { container ->
                container.points.forEach { point ->
                    val existing = pointsByTimestamp[point.timestamp] ?: AggregatedPoint()
                    pointsByTimestamp[point.timestamp] = existing.copy(
                        cpuMillicores = existing.cpuMillicores + point.cpuMillicores,
                        memoryBytes = existing.memoryBytes + point.memoryBytes,
                    )
                }
            }

            val sortedValues = pointsByTimestamp.toSortedMap().values.toList()
            item.copy(
                cpuMillicoresSeries = sortedValues.map { it.cpuMillicores },
                memoryBytesSeries = sortedValues.map { it.memoryBytes },
            )
        }
    }

    private data class PodResourceKey(
        val namespace: String,
        val podName: String,
    )

    private data class AggregatedPoint(
        val cpuMillicores: Long = 0L,
        val memoryBytes: Long = 0L,
    )

    private companion object {
        const val TAG = "ResourceListVM"
        const val POD_KIND = "Pod"
    }
}
