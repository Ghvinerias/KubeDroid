package com.kubedroid.app.pods

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import com.kubedroid.core.network.resources.ResourceDetailRepository
import com.kubedroid.core.network.resources.ResourceNotFoundException
import com.kubedroid.core.network.resources.YamlEditResult
import com.kubedroid.feature.resources.detail.ResourceDetailTab
import com.kubedroid.feature.resources.detail.ResourceDetailUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PodClusterAccess(
    val apiServer: String,
    val bearerToken: String?,
)

data class PodResourceScreenState(
    val detailState: ResourceDetailUiState = ResourceDetailUiState.Loading,
    val selectedTab: ResourceDetailTab = ResourceDetailTab.YAML,
    val access: PodClusterAccess? = null,
    val yamlApplyError: Throwable? = null,
)

@HiltViewModel
class PodResourceViewModel @Inject constructor(
    private val resourceDetailRepository: ResourceDetailRepository,
    private val kubeConfigRepository: KubeConfigRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "PodResourceVM"
    }

    private val _uiState = MutableStateFlow(PodResourceScreenState())
    val uiState: StateFlow<PodResourceScreenState> = _uiState.asStateFlow()

    init {
        refreshAccess()
    }

    fun refreshAccess() {
        viewModelScope.launch {
            val loaded = kubeConfigRepository.load()
            if (loaded.isFailure) {
                Log.e(TAG, "Failed to load kubeconfig for pod access", loaded.exceptionOrNull())
                _uiState.update { it.copy(access = null) }
                return@launch
            }

            val config = loaded.getOrThrow()
            val currentContextName = config.currentContext
            val context = config.contexts.firstOrNull { it.name == currentContextName }
            val cluster = config.clusters.firstOrNull { it.name == context?.cluster }
            val user = config.users.firstOrNull { it.name == context?.user }
            val token = user?.raw?.get("token") as? String
            val server = cluster?.server

            if (server.isNullOrBlank()) {
                _uiState.update { it.copy(access = null) }
            } else {
                _uiState.update {
                    it.copy(
                        access = PodClusterAccess(
                            apiServer = server,
                            bearerToken = token,
                        ),
                    )
                }
            }
        }
    }

    fun load(kind: String, name: String, namespace: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(detailState = ResourceDetailUiState.Loading, yamlApplyError = null) }

            resourceDetailRepository.getResourceDetail(
                kind = kind,
                name = name,
                namespace = namespace,
            ).fold(
                onSuccess = { detail ->
                    _uiState.update {
                        it.copy(
                            detailState = ResourceDetailUiState.Content(detail = detail),
                            selectedTab = ResourceDetailTab.YAML,
                            yamlApplyError = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    val state = when (throwable) {
                        is ResourceNotFoundException -> ResourceDetailUiState.NotFound
                        else -> ResourceDetailUiState.Error(throwable)
                    }
                    Log.e(TAG, "Failed to load resource detail", throwable)
                    _uiState.update { it.copy(detailState = state, yamlApplyError = null) }
                },
            )
        }
    }

    fun retry() {
        val content = _uiState.value.detailState as? ResourceDetailUiState.Content ?: return
        load(kind = content.detail.kind, name = content.detail.name, namespace = content.detail.namespace)
    }

    fun setTab(tab: ResourceDetailTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun updateYamlDraft(value: String) {
        _uiState.update { state ->
            val content = state.detailState as? ResourceDetailUiState.Content ?: return@update state
            state.copy(
                detailState = content.copy(yamlDraft = value),
                yamlApplyError = null,
            )
        }
    }

    fun applyYaml() {
        val content = _uiState.value.detailState as? ResourceDetailUiState.Content ?: return

        viewModelScope.launch {
            _uiState.update { state ->
                val latest = state.detailState as? ResourceDetailUiState.Content ?: return@update state
                state.copy(
                    detailState = latest.copy(isApplyingYaml = true, lastYamlEditResult = null),
                    yamlApplyError = null,
                )
            }

            val result = resourceDetailRepository.applyYaml(
                kind = content.detail.kind,
                name = content.detail.name,
                yaml = content.yamlDraft,
                namespace = content.detail.namespace,
            )

            result.fold(
                onSuccess = { editResult ->
                    _uiState.update { state ->
                        val latest = state.detailState as? ResourceDetailUiState.Content ?: return@update state
                        state.copy(
                            detailState = latest.copy(
                                isApplyingYaml = false,
                                lastYamlEditResult = editResult,
                            ),
                        )
                    }

                    if (editResult is YamlEditResult.Applied) {
                        load(
                            kind = content.detail.kind,
                            name = content.detail.name,
                            namespace = content.detail.namespace,
                        )
                    }
                },
                onFailure = { throwable ->
                    Log.e(TAG, "Failed to apply YAML", throwable)
                    _uiState.update { state ->
                        val latest = state.detailState as? ResourceDetailUiState.Content ?: return@update state
                        state.copy(
                            detailState = latest.copy(
                                isApplyingYaml = false,
                                lastYamlEditResult = null,
                            ),
                            yamlApplyError = throwable,
                        )
                    }
                },
            )
        }
    }
}
