package com.kubedroid.feature.rbac.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.feature.rbac.domain.model.CanIResult
import com.kubedroid.feature.rbac.domain.repository.RbacRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class RbacViewModel @Inject constructor(
    private val repository: RbacRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RbacUiState())
    val uiState: StateFlow<RbacUiState> = _uiState.asStateFlow()

    init {
        refreshForNamespace(DEFAULT_NAMESPACE)
    }

    fun onIntent(intent: RbacIntent) {
        when (intent) {
            RbacIntent.Refresh -> refreshForNamespace(_uiState.value.selectedNamespace)
            is RbacIntent.SelectNamespace -> {
                val normalized = intent.namespace.trim().ifBlank { DEFAULT_NAMESPACE }
                _uiState.update {
                    it.copy(
                        selectedNamespace = normalized,
                        canINamespace = normalized,
                    )
                }
                refreshForNamespace(normalized)
            }

            is RbacIntent.SelectTab -> _uiState.update { it.copy(selectedTab = intent.tab) }
            is RbacIntent.UpdateCanIResource -> {
                _uiState.update {
                    it.copy(
                        canIResourceInput = intent.resource,
                        canIResult = null,
                        canICheckSummary = null,
                    )
                }
            }

            is RbacIntent.SelectCanIVerb -> {
                _uiState.update {
                    it.copy(
                        canIVerb = intent.verb,
                        canIResult = null,
                        canICheckSummary = null,
                    )
                }
            }

            is RbacIntent.SelectCanINamespace -> {
                _uiState.update {
                    it.copy(
                        canINamespace = intent.namespace,
                        canIResult = null,
                        canICheckSummary = null,
                    )
                }
            }

            RbacIntent.CheckCanI -> checkCanI()
        }
    }

    private fun refreshForNamespace(namespace: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val selectedNamespace = namespace.trim().ifBlank { DEFAULT_NAMESPACE }
            try {
                val roles = repository.listRoles(selectedNamespace) + repository.listClusterRoles()
                val bindings = repository.listRoleBindings(selectedNamespace) + repository.listClusterRoleBindings()

                val namespaceOptions = buildSet {
                    add(DEFAULT_NAMESPACE)
                    add(selectedNamespace)
                    roles.mapNotNullTo(this) { it.namespace?.trim()?.ifBlank { null } }
                    bindings.mapNotNullTo(this) { it.namespace?.trim()?.ifBlank { null } }
                }.sorted()

                _uiState.update {
                    it.copy(
                        selectedNamespace = selectedNamespace,
                        namespaceOptions = namespaceOptions,
                        roles = roles.map { role ->
                            RoleListItemUiModel(
                                name = role.name,
                                namespace = role.namespace,
                                ruleCount = role.rules.size,
                                isDangerous = role.rules.any { rule ->
                                    rule.contains(DANGEROUS_MARKER, ignoreCase = true)
                                },
                            )
                        },
                        bindings = bindings.map { binding ->
                            BindingListItemUiModel(
                                name = binding.name,
                                namespace = binding.namespace,
                                roleRefName = binding.roleRefName,
                                subjectCount = binding.subjectNames.size,
                            )
                        },
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        roles = emptyList(),
                        bindings = emptyList(),
                        canIResult = CanIResult.Unknown,
                        canICheckSummary = null,
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun checkCanI() {
        val snapshot = _uiState.value
        val resource = snapshot.canIResourceInput.trim()
        if (resource.isEmpty()) {
            _uiState.update {
                it.copy(
                    canIResult = CanIResult.Unknown,
                    canICheckSummary = null,
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingCanI = true) }

            val verb = snapshot.canIVerb.trim().ifBlank { DEFAULT_VERB }
            val namespace = snapshot.canINamespace?.trim()?.ifBlank { null }
            val result = runCatching {
                repository.canI(
                    verb = verb,
                    resource = resource,
                    namespace = namespace,
                    subjectName = "self",
                )
            }.getOrDefault(CanIResult.Unknown)

            _uiState.update {
                it.copy(
                    isCheckingCanI = false,
                    canIResult = result,
                    canICheckSummary = CanICheckSummary(
                        verb = verb,
                        resource = resource,
                        namespace = namespace,
                    ),
                )
            }
        }
    }
}
