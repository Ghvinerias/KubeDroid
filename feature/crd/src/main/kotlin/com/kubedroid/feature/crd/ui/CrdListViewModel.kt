package com.kubedroid.feature.crd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.feature.crd.CrdRepository
import com.kubedroid.feature.crd.FavouriteCrdRepository
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import com.kubedroid.feature.pods.resources.ResourceListUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class CrdListViewModel @Inject constructor(
    private val crdRepository: CrdRepository,
    private val favouriteCrdRepository: FavouriteCrdRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CrdListScreenState())
    val uiState: StateFlow<CrdListScreenState> = _uiState.asStateFlow()

    private var allCrds: List<CustomResourceDefinition> = emptyList()
    private var favouriteKeys: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            favouriteCrdRepository.observeFavouriteCrds().collect { favourites ->
                favouriteKeys = favourites.map { it.key() }.toSet()
                updateListState()
            }
        }
    }

    fun onIntent(intent: CrdListIntent) {
        when (intent) {
            CrdListIntent.Load,
            CrdListIntent.Retry,
            -> loadCrds()

            is CrdListIntent.SearchQueryChanged -> {
                _uiState.update { it.copy(searchQuery = intent.query) }
                updateListState()
            }

            is CrdListIntent.ToggleFavourite -> {
                setFavourite(intent.crd, intent.favourite)
            }
        }
    }

    private fun loadCrds() {
        viewModelScope.launch {
            _uiState.update { it.copy(listState = ResourceListUiState.Loading) }
            crdRepository.listCrds().fold(
                onSuccess = { crds ->
                    allCrds = crds
                    updateListState()
                },
                onFailure = { throwable ->
                    _uiState.update { it.copy(listState = ResourceListUiState.Error(throwable)) }
                },
            )
        }
    }

    private fun setFavourite(
        crd: CustomResourceDefinition,
        favourite: Boolean,
    ) {
        viewModelScope.launch {
            favouriteCrdRepository.setFavourite(
                crd = crd,
                favourite = favourite,
            )
        }
    }

    private fun updateListState() {
        val query = _uiState.value.searchQuery.trim()
        val filtered = allCrds
            .asSequence()
            .filter {
                query.isBlank() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.kind.contains(query, ignoreCase = true) ||
                    it.group.contains(query, ignoreCase = true)
            }
            .map { definition ->
                CrdListItem(
                    definition = definition,
                    isFavourite = definition.key() in favouriteKeys,
                )
            }
            .sortedWith(
                compareByDescending<CrdListItem> { it.isFavourite }
                    .thenBy { it.definition.name },
            )
            .toList()

        _uiState.update {
            it.copy(
                listState = if (filtered.isEmpty()) {
                    ResourceListUiState.Empty
                } else {
                    ResourceListUiState.Success(filtered)
                },
            )
        }
    }
}
