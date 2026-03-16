package com.kubedroid.feature.crd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.namespace.NamespaceStore
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import com.kubedroid.core.network.pods.NamespaceRepository
import com.kubedroid.feature.crd.CrdRepository
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import com.kubedroid.feature.pods.resources.ResourceListItem
import com.kubedroid.feature.pods.resources.ResourceListUiState
import com.kubedroid.feature.pods.resources.ResourceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class CrdResourceListViewModel @Inject constructor(
    private val crdRepository: CrdRepository,
    private val namespaceRepository: NamespaceRepository,
    private val namespaceStore: NamespaceStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CrdResourceListScreenState())
    val uiState: StateFlow<CrdResourceListScreenState> = _uiState.asStateFlow()

    init {
        observeSelectedNamespace()
    }

    fun onIntent(intent: CrdResourceListIntent) {
        when (intent) {
            is CrdResourceListIntent.BindCrd -> bindCrd(intent.crd)
            CrdResourceListIntent.Load,
            CrdResourceListIntent.Retry,
            -> loadResources()

            CrdResourceListIntent.OpenNamespacePicker -> {
                _uiState.update { it.copy(isNamespacePickerVisible = true) }
            }

            CrdResourceListIntent.DismissNamespacePicker -> {
                _uiState.update { it.copy(isNamespacePickerVisible = false) }
            }

            is CrdResourceListIntent.SelectNamespace -> {
                _uiState.update {
                    it.copy(
                        isNamespacePickerVisible = false,
                    )
                }
                viewModelScope.launch {
                    namespaceStore.setNamespace(intent.namespace)
                }
            }
        }
    }

    private fun bindCrd(crd: CustomResourceDefinition) {
        _uiState.update {
            it.copy(
                selectedCrd = crd,
                selectedNamespace = if (crd.isClusterScoped()) "" else it.selectedNamespace,
            )
        }
        if (crd.isClusterScoped()) {
            loadResources()
            return
        }

        viewModelScope.launch {
            namespaceRepository.listNamespaces().onSuccess { namespaces ->
                val currentNamespace = _uiState.value.selectedNamespace
                val resolvedNamespace = when {
                    currentNamespace in namespaces -> currentNamespace
                    DEFAULT_NAMESPACE in namespaces -> DEFAULT_NAMESPACE
                    else -> namespaces.firstOrNull().orEmpty()
                }
                _uiState.update {
                    it.copy(
                        namespaces = namespaces,
                        selectedNamespace = resolvedNamespace,
                    )
                }
                if (resolvedNamespace.isNotBlank() && resolvedNamespace != currentNamespace) {
                    viewModelScope.launch {
                        namespaceStore.setNamespace(resolvedNamespace)
                    }
                }
            }
            loadResources()
        }
    }

    private fun loadResources() {
        val crd = _uiState.value.selectedCrd ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(listState = ResourceListUiState.Loading) }

            val namespace = if (crd.isClusterScoped()) "" else _uiState.value.selectedNamespace.trim()
            crdRepository.listCustomResources(
                crd = crd,
                namespace = namespace,
            ).fold(
                onSuccess = { resources ->
                    val rows = resources.map {
                        ResourceListItem(
                            id = if (it.namespace.isBlank()) it.name else "${it.namespace}/${it.name}",
                            name = it.name,
                            namespace = it.namespace.ifBlank { CLUSTER_SCOPE_NAMESPACE_LABEL },
                            kind = crd.kind,
                            status = ResourceStatus.UNKNOWN,
                        )
                    }

                    _uiState.update {
                        it.copy(
                            listState = if (rows.isEmpty()) {
                                ResourceListUiState.Empty
                            } else {
                                ResourceListUiState.Success(rows)
                            },
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update { it.copy(listState = ResourceListUiState.Error(throwable)) }
                },
            )
        }
    }

    private fun observeSelectedNamespace() {
        viewModelScope.launch {
            namespaceStore.selectedNamespace
                .distinctUntilChanged()
                .collect { namespace ->
                    val current = _uiState.value
                    if (current.selectedCrd?.isClusterScoped() == true) {
                        return@collect
                    }
                    val effectiveNamespace = if (namespace.isAllNamespacesSelection()) {
                        DEFAULT_NAMESPACE
                    } else {
                        namespace
                    }
                    _uiState.update { state -> state.copy(selectedNamespace = effectiveNamespace) }
                    if (current.selectedCrd != null) {
                        loadResources()
                    }
                }
        }
    }
}

private const val CLUSTER_SCOPE_NAMESPACE_LABEL = "-"

private fun CustomResourceDefinition.isClusterScoped(): Boolean =
    scope.equals("Cluster", ignoreCase = true)
