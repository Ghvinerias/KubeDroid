package com.kubedroid.feature.crd.ui

import com.kubedroid.core.network.resources.YamlEditResult
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import com.kubedroid.feature.pods.resources.ResourceListItem
import com.kubedroid.feature.pods.resources.ResourceListUiState

data class CrdListItem(
    val definition: CustomResourceDefinition,
    val isFavourite: Boolean,
)

data class CrdListScreenState(
    val searchQuery: String = "",
    val listState: ResourceListUiState<CrdListItem> = ResourceListUiState.Loading,
)

sealed interface CrdListIntent {
    data object Load : CrdListIntent
    data object Retry : CrdListIntent
    data class SearchQueryChanged(val query: String) : CrdListIntent
    data class ToggleFavourite(val crd: CustomResourceDefinition, val favourite: Boolean) : CrdListIntent
}

data class CrdResourceListScreenState(
    val selectedCrd: CustomResourceDefinition? = null,
    val selectedNamespace: String = DEFAULT_NAMESPACE,
    val namespaces: List<String> = emptyList(),
    val isNamespacePickerVisible: Boolean = false,
    val listState: ResourceListUiState<ResourceListItem> = ResourceListUiState.Loading,
)

sealed interface CrdResourceListIntent {
    data class BindCrd(val crd: CustomResourceDefinition) : CrdResourceListIntent
    data object Load : CrdResourceListIntent
    data object Retry : CrdResourceListIntent
    data object OpenNamespacePicker : CrdResourceListIntent
    data object DismissNamespacePicker : CrdResourceListIntent
    data class SelectNamespace(val namespace: String) : CrdResourceListIntent
}

sealed interface CrdResourceDetailContentState {
    data object Loading : CrdResourceDetailContentState
    data class Content(
        val crd: CustomResourceDefinition,
        val name: String,
        val namespace: String,
        val yaml: String,
        val yamlDraft: String,
        val isEditingEnabled: Boolean = false,
        val isEditing: Boolean = false,
        val isApplying: Boolean = false,
        val lastApplyResult: YamlEditResult? = null,
        val applyError: Throwable? = null,
    ) : CrdResourceDetailContentState

    data class Error(val cause: Throwable?) : CrdResourceDetailContentState
}

data class CrdResourceDetailScreenState(
    val state: CrdResourceDetailContentState = CrdResourceDetailContentState.Loading,
    val isUnknownTypeConfirmationVisible: Boolean = false,
)

sealed interface CrdResourceDetailIntent {
    data class BindTarget(
        val crd: CustomResourceDefinition,
        val name: String,
        val namespace: String,
    ) : CrdResourceDetailIntent

    data object Retry : CrdResourceDetailIntent
    data object StartEditing : CrdResourceDetailIntent
    data object CancelEditing : CrdResourceDetailIntent
    data class YamlDraftChanged(val yaml: String) : CrdResourceDetailIntent
    data object ApplyYaml : CrdResourceDetailIntent
    data object DismissUnknownTypeConfirmation : CrdResourceDetailIntent
    data object ConfirmUnknownTypeEditing : CrdResourceDetailIntent
}

internal const val DEFAULT_NAMESPACE = "default"

internal fun CustomResourceDefinition.key(): String = "$group/$version/$kind"
