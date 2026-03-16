package com.kubedroid.app.resources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedroid.core.network.resources.ResourceDetailRepository
import com.kubedroid.core.network.resources.YamlEditResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ManifestDeployUiState(
    val yamlDraft: String = DEFAULT_MANIFEST_TEMPLATE,
    val isApplying: Boolean = false,
    val lastYamlEditResult: YamlEditResult? = null,
    val applyError: Throwable? = null,
)

@HiltViewModel
class ManifestDeployViewModel @Inject constructor(
    private val resourceDetailRepository: ResourceDetailRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManifestDeployUiState())
    val uiState: StateFlow<ManifestDeployUiState> = _uiState.asStateFlow()

    fun updateYamlDraft(value: String) {
        _uiState.update {
            it.copy(
                yamlDraft = value,
                applyError = null,
            )
        }
    }

    fun applyYaml() {
        val yaml = _uiState.value.yamlDraft
        val target = runCatching { parseTargetFromYaml(yaml) }
        if (target.isFailure) {
            _uiState.update {
                it.copy(
                    applyError = target.exceptionOrNull(),
                    lastYamlEditResult = null,
                    isApplying = false,
                )
            }
            return
        }

        val parsedTarget = target.getOrThrow()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isApplying = true,
                    lastYamlEditResult = null,
                    applyError = null,
                )
            }

            resourceDetailRepository.applyYaml(
                kind = parsedTarget.kind,
                name = parsedTarget.name,
                yaml = yaml,
                namespace = parsedTarget.namespace,
            ).fold(
                onSuccess = { result ->
                    _uiState.update {
                        it.copy(
                            isApplying = false,
                            lastYamlEditResult = result,
                            applyError = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isApplying = false,
                            lastYamlEditResult = null,
                            applyError = throwable,
                        )
                    }
                },
            )
        }
    }
}

private data class ParsedTarget(
    val kind: String,
    val name: String,
    val namespace: String?,
)

private fun parseTargetFromYaml(yaml: String): ParsedTarget {
    val trimmed = yaml.trim()
    require(trimmed.isNotEmpty()) { "YAML manifest is empty" }

    val kind = Regex("^kind:\\s*([^#\\n]+)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.sanitizeYamlScalar()
        .orEmpty()
    require(kind.isNotBlank()) { "YAML must include 'kind'" }

    val metadataBlock = Regex("^metadata:\\s*\\n((?:[ \\t].*\\n?)*)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    require(metadataBlock.isNotBlank()) { "YAML must include 'metadata.name'" }

    val name = Regex("^[ \\t]+name:\\s*([^#\\n]+)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        .find(metadataBlock)
        ?.groupValues
        ?.getOrNull(1)
        ?.sanitizeYamlScalar()
        .orEmpty()
    require(name.isNotBlank()) { "YAML must include 'metadata.name'" }

    val namespace = Regex("^[ \\t]+namespace:\\s*([^#\\n]+)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        .find(metadataBlock)
        ?.groupValues
        ?.getOrNull(1)
        ?.sanitizeYamlScalar()
        ?.takeIf { it.isNotBlank() }

    return ParsedTarget(
        kind = kind,
        name = name,
        namespace = namespace,
    )
}

private fun String.sanitizeYamlScalar(): String {
    val value = trim()
    if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\'')))) {
        return value.substring(1, value.length - 1).trim()
    }
    return value
}

private const val DEFAULT_MANIFEST_TEMPLATE = """
apiVersion: v1
kind: ConfigMap
metadata:
  name: example-config
  namespace: default
data:
  key: value
"""
