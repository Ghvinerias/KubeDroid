package com.kubedroid.feature.crd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.kubedroid.core.network.resources.YamlEditResult
import com.kubedroid.feature.crd.CrdRepository
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import dagger.hilt.android.lifecycle.HiltViewModel
import io.kubernetes.client.util.Yaml
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class CrdResourceDetailViewModel @Inject constructor(
    private val repository: CrdRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CrdResourceDetailScreenState())
    val uiState: StateFlow<CrdResourceDetailScreenState> = _uiState.asStateFlow()

    private var currentCrd: CustomResourceDefinition? = null
    private var currentName: String = ""
    private var currentNamespace: String = ""

    fun onIntent(intent: CrdResourceDetailIntent) {
        when (intent) {
            is CrdResourceDetailIntent.BindTarget -> bindTarget(
                crd = intent.crd,
                name = intent.name,
                namespace = intent.namespace,
            )

            CrdResourceDetailIntent.Retry -> loadResource()
            CrdResourceDetailIntent.StartEditing -> startEditing()
            CrdResourceDetailIntent.CancelEditing -> cancelEditing()
            is CrdResourceDetailIntent.YamlDraftChanged -> updateDraft(intent.yaml)
            CrdResourceDetailIntent.ApplyYaml -> applyYaml()
            CrdResourceDetailIntent.DismissUnknownTypeConfirmation -> {
                _uiState.update { it.copy(isUnknownTypeConfirmationVisible = false) }
            }

            CrdResourceDetailIntent.ConfirmUnknownTypeEditing -> {
                val content = _uiState.value.state as? CrdResourceDetailContentState.Content ?: return
                _uiState.update {
                    it.copy(
                        state = content.copy(
                            isEditingEnabled = true,
                            isEditing = true,
                        ),
                        isUnknownTypeConfirmationVisible = false,
                    )
                }
            }
        }
    }

    private fun bindTarget(
        crd: CustomResourceDefinition,
        name: String,
        namespace: String,
    ) {
        currentCrd = crd
        currentName = name.trim()
        currentNamespace = namespace.trim()
        loadResource()
    }

    private fun loadResource() {
        val crd = currentCrd ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    state = CrdResourceDetailContentState.Loading,
                    isUnknownTypeConfirmationVisible = false,
                )
            }

            repository.getCustomResource(
                crd = crd,
                name = currentName,
                namespace = currentNamespace,
            ).fold(
                onSuccess = { resource ->
                    val yaml = mapJsonToYaml(resource.rawJson)
                    _uiState.update {
                        it.copy(
                            state = CrdResourceDetailContentState.Content(
                                crd = crd,
                                name = resource.name,
                                namespace = resource.namespace,
                                yaml = yaml,
                                yamlDraft = yaml,
                                isEditingEnabled = isKnownEditableType(crd),
                            ),
                            isUnknownTypeConfirmationVisible = false,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            state = CrdResourceDetailContentState.Error(throwable),
                            isUnknownTypeConfirmationVisible = false,
                        )
                    }
                },
            )
        }
    }

    private fun startEditing() {
        val content = _uiState.value.state as? CrdResourceDetailContentState.Content ?: return
        if (content.isEditingEnabled) {
            _uiState.update { it.copy(state = content.copy(isEditing = true)) }
        } else {
            _uiState.update { it.copy(isUnknownTypeConfirmationVisible = true) }
        }
    }

    private fun cancelEditing() {
        val content = _uiState.value.state as? CrdResourceDetailContentState.Content ?: return
        _uiState.update {
            it.copy(
                state = content.copy(
                    isEditing = false,
                    yamlDraft = content.yaml,
                    lastApplyResult = null,
                    applyError = null,
                ),
            )
        }
    }

    private fun updateDraft(yaml: String) {
        val content = _uiState.value.state as? CrdResourceDetailContentState.Content ?: return
        _uiState.update {
            it.copy(
                state = content.copy(
                    yamlDraft = yaml,
                    lastApplyResult = null,
                    applyError = null,
                ),
            )
        }
    }

    private fun applyYaml() {
        val content = _uiState.value.state as? CrdResourceDetailContentState.Content ?: return
        if (!content.isEditing || content.isApplying || !content.isEditingEnabled) {
            return
        }
        val submittedYaml = content.yamlDraft

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    state = content.copy(
                        isApplying = true,
                        lastApplyResult = null,
                        applyError = null,
                    ),
                )
            }

            repository.applyCustomResourceYaml(
                crd = content.crd,
                name = content.name,
                namespace = content.namespace,
                yaml = submittedYaml,
            ).fold(
                onSuccess = { result ->
                    val current = _uiState.value.state as? CrdResourceDetailContentState.Content ?: return@fold
                    _uiState.update {
                        it.copy(
                            state = when (result) {
                                is YamlEditResult.Applied,
                                YamlEditResult.NoChanges,
                                -> current.copy(
                                    yaml = submittedYaml,
                                    yamlDraft = submittedYaml,
                                    isEditing = false,
                                    isApplying = false,
                                    lastApplyResult = result,
                                    applyError = null,
                                )

                                is YamlEditResult.Conflict -> current.copy(
                                    isApplying = false,
                                    lastApplyResult = result,
                                    applyError = null,
                                )
                            },
                        )
                    }
                },
                onFailure = { throwable ->
                    val current = _uiState.value.state as? CrdResourceDetailContentState.Content ?: return@fold
                    _uiState.update {
                        it.copy(
                            state = current.copy(
                                isApplying = false,
                                applyError = throwable,
                                lastApplyResult = null,
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun mapJsonToYaml(rawJson: String): String {
        return runCatching {
            val parsed = gson.fromJson(rawJson, Any::class.java)
            Yaml.dump(parsed)
        }.getOrElse {
            rawJson
        }
    }

    private fun isKnownEditableType(crd: CustomResourceDefinition): Boolean {
        return "${crd.group}/${crd.version}/${crd.kind.lowercase()}" in KNOWN_EDITABLE_CRD_TYPES
    }

    private companion object {
        val gson: Gson = Gson()
        val KNOWN_EDITABLE_CRD_TYPES = setOf(
            "cert-manager.io/v1/certificate",
            "external-secrets.io/v1beta1/externalsecret",
            "argoproj.io/v1alpha1/application",
        )
    }
}
