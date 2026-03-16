package com.kubedroid.feature.helm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.namespace.NamespaceStore
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import com.kubedroid.core.network.pods.NamespaceRepository
import com.kubedroid.feature.helm.HelmRepository
import com.kubedroid.feature.helm.model.HelmRelease
import com.kubedroid.feature.helm.model.HelmRevision
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HelmScreenState(
    val namespace: String = "",
    val availableNamespaces: List<String> = emptyList(),
    val listState: HelmUiState = HelmUiState.Loading,
    val selectedRelease: HelmRelease? = null,
    val selectedTab: HelmDetailTab = HelmDetailTab.INFO,
    val revisions: List<HelmRevision> = emptyList(),
    val manifest: String = "",
    val isLoadingDetail: Boolean = false,
)

@HiltViewModel
class HelmViewModel @Inject constructor(
    private val repository: HelmRepository,
    private val namespaceRepository: NamespaceRepository,
    private val namespaceStore: NamespaceStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HelmScreenState())
    val uiState: StateFlow<HelmScreenState> = _uiState.asStateFlow()
    private val _selectedNamespace = MutableStateFlow(DEFAULT_NAMESPACE)
    val selectedNamespace: StateFlow<String> = _selectedNamespace.asStateFlow()

    private var listRequestJob: Job? = null
    private var detailRequestJob: Job? = null

    init {
        observeSelectedNamespace()
        loadNamespaces()
    }

    fun refreshList() {
        val namespace = _selectedNamespace.value
        if (namespace.isBlank()) {
            loadNamespaces()
            return
        }
        loadList(namespace)
    }

    fun selectNamespace(namespace: String) {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isBlank() || normalizedNamespace == _selectedNamespace.value) {
            return
        }
        viewModelScope.launch {
            namespaceStore.setNamespace(normalizedNamespace)
        }
    }

    private fun loadNamespaces() {
        viewModelScope.launch {
            namespaceRepository.listNamespaces().fold(
                onSuccess = { namespaces ->
                    val availableNamespaces = namespaces.filter { it.isNotBlank() }
                    _uiState.update { it.copy(availableNamespaces = availableNamespaces) }
                    if (availableNamespaces.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                namespace = "",
                                listState = HelmUiState.Empty,
                                selectedRelease = null,
                            )
                        }
                    } else {
                        val selected = _selectedNamespace.value
                        if (selected in availableNamespaces) {
                            loadList(selected)
                        } else {
                            val fallbackNamespace = if (DEFAULT_NAMESPACE in availableNamespaces) {
                                DEFAULT_NAMESPACE
                            } else {
                                availableNamespaces.first()
                            }
                            viewModelScope.launch {
                                namespaceStore.setNamespace(fallbackNamespace)
                            }
                        }
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            listState = HelmUiState.Error(throwable),
                            selectedRelease = null,
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
                    val effectiveNamespace = if (namespace.isAllNamespacesSelection()) {
                        DEFAULT_NAMESPACE
                    } else {
                        namespace
                    }
                    _selectedNamespace.value = effectiveNamespace
                    loadList(effectiveNamespace)
                }
        }
    }

    private fun loadList(namespace: String) {
        listRequestJob?.cancel()
        detailRequestJob?.cancel()
        _uiState.update { it.copy(listState = HelmUiState.Loading, selectedRelease = null) }
        _uiState.update { it.copy(namespace = namespace, revisions = emptyList(), manifest = "", isLoadingDetail = false) }
        listRequestJob = viewModelScope.launch {
            repository.listReleases(namespace).fold(
                onSuccess = { releases ->
                    _uiState.update {
                        it.copy(
                            listState = if (releases.isEmpty()) HelmUiState.Empty else HelmUiState.Success(releases),
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update { it.copy(listState = HelmUiState.Error(throwable)) }
                },
            )
        }
    }

    fun openRelease(release: HelmRelease) {
        _uiState.update {
            it.copy(
                selectedRelease = release,
                selectedTab = HelmDetailTab.INFO,
                revisions = emptyList(),
                manifest = "",
                isLoadingDetail = true,
            )
        }
        detailRequestJob?.cancel()
        detailRequestJob = viewModelScope.launch {
            val history = repository.getReleaseHistory(name = release.name, namespace = release.namespace).getOrElse { emptyList() }
            val manifest = repository.getReleaseManifest(name = release.name, namespace = release.namespace).getOrElse { "" }
            _uiState.update {
                it.copy(
                    revisions = history,
                    manifest = manifest,
                    isLoadingDetail = false,
                )
            }
        }
    }

    fun closeRelease() {
        _uiState.update { it.copy(selectedRelease = null, revisions = emptyList(), manifest = "", isLoadingDetail = false) }
    }

    fun setSelectedTab(tab: HelmDetailTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    private companion object {
        const val DEFAULT_NAMESPACE = "default"
    }
}
