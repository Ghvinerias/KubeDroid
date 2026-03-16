package com.kubedroid.feature.rbac.ui

import com.kubedroid.feature.rbac.domain.model.CanIResult

enum class RbacTab {
    Roles,
    Bindings,
    CanI,
}

data class RoleListItemUiModel(
    val name: String,
    val namespace: String?,
    val ruleCount: Int,
    val isDangerous: Boolean,
)

data class BindingListItemUiModel(
    val name: String,
    val namespace: String?,
    val roleRefName: String,
    val subjectCount: Int,
)

data class CanICheckSummary(
    val verb: String,
    val resource: String,
    val namespace: String?,
)

data class RbacUiState(
    val selectedTab: RbacTab = RbacTab.Roles,
    val selectedNamespace: String = DEFAULT_NAMESPACE,
    val namespaceOptions: List<String> = listOf(DEFAULT_NAMESPACE),
    val roles: List<RoleListItemUiModel> = emptyList(),
    val bindings: List<BindingListItemUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val canIResourceInput: String = "",
    val canIVerb: String = DEFAULT_VERB,
    val canINamespace: String? = DEFAULT_NAMESPACE,
    val isCheckingCanI: Boolean = false,
    val canIResult: CanIResult? = null,
    val canICheckSummary: CanICheckSummary? = null,
)

sealed interface RbacIntent {
    data object Refresh : RbacIntent
    data class SelectTab(val tab: RbacTab) : RbacIntent
    data class SelectNamespace(val namespace: String) : RbacIntent
    data class UpdateCanIResource(val resource: String) : RbacIntent
    data class SelectCanIVerb(val verb: String) : RbacIntent
    data class SelectCanINamespace(val namespace: String?) : RbacIntent
    data object CheckCanI : RbacIntent
}

const val DEFAULT_NAMESPACE = "default"
const val DEFAULT_VERB = "get"
const val DANGEROUS_MARKER = "DANGEROUS:"

val SUPPORTED_CAN_I_VERBS: List<String> = listOf(
    "get",
    "list",
    "watch",
    "create",
    "update",
    "patch",
    "delete",
)
