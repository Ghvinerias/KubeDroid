package com.kubedroid.feature.resources.detail

import com.kubedroid.core.network.resources.ResourceDetail
import com.kubedroid.core.network.resources.YamlEditResult

/**
 * Screen state for resource detail and YAML editing.
 */
sealed class ResourceDetailUiState {
    data object Loading : ResourceDetailUiState()
    data object NotFound : ResourceDetailUiState()
    data class Content(
        val detail: ResourceDetail,
        val yamlDraft: String = detail.yaml,
        val isApplyingYaml: Boolean = false,
        val lastYamlEditResult: YamlEditResult? = null,
    ) : ResourceDetailUiState()
    data class Error(val cause: Throwable?) : ResourceDetailUiState()
}
