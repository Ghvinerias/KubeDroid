package com.kubedroid.feature.resources.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.kubedroid.core.network.resources.YamlEditResult
import com.kubedroid.feature.resources.R

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun YAMLEditorScreen(
    title: String? = null,
    applyActionLabel: String? = null,
    yamlDraft: String,
    isApplying: Boolean,
    lastYamlEditResult: YamlEditResult?,
    applyError: Throwable?,
    onYamlDraftChange: (String) -> Unit,
    onApplyClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenPadding = dimensionResource(id = R.dimen.resource_detail_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.resource_detail_content_spacing)
    val fieldMinLines = integerResourceCompat(R.integer.yaml_editor_min_lines)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = title ?: stringResource(id = R.string.yaml_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.resource_detail_back_cd),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = onApplyClick,
                        enabled = !isApplying,
                    ) {
                        Text(text = applyActionLabel ?: stringResource(id = R.string.yaml_editor_apply_action))
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            EditorStatusCard(
                isApplying = isApplying,
                lastYamlEditResult = lastYamlEditResult,
                applyError = applyError,
            )

            OutlinedTextField(
                value = yamlDraft,
                onValueChange = onYamlDraftChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = fieldMinLines,
                label = { Text(text = stringResource(id = R.string.yaml_editor_field_label)) },
                placeholder = { Text(text = stringResource(id = R.string.yaml_editor_placeholder)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
            )
        }
    }
}

@Composable
private fun EditorStatusCard(
    isApplying: Boolean,
    lastYamlEditResult: YamlEditResult?,
    applyError: Throwable?,
) {
    if (!isApplying && lastYamlEditResult == null && applyError == null) {
        return
    }

    val status = when {
        isApplying -> EditorStatus(
            message = stringResource(id = R.string.yaml_editor_applying),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        applyError != null -> EditorStatus(
            message = applyError.localizedMessage ?: stringResource(id = R.string.yaml_editor_error_generic),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )

        lastYamlEditResult is YamlEditResult.Applied -> {
            val version = lastYamlEditResult.resourceVersion
            EditorStatus(
                message = if (version.isNullOrBlank()) {
                    stringResource(id = R.string.yaml_editor_result_applied)
                } else {
                    stringResource(id = R.string.yaml_editor_result_applied_with_version, version)
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        lastYamlEditResult is YamlEditResult.NoChanges -> EditorStatus(
            message = stringResource(id = R.string.yaml_editor_result_no_changes),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        lastYamlEditResult is YamlEditResult.Conflict -> {
            val version = lastYamlEditResult.currentResourceVersion
            EditorStatus(
                message = if (version.isNullOrBlank()) {
                    stringResource(id = R.string.yaml_editor_result_conflict_unknown)
                } else {
                    stringResource(id = R.string.yaml_editor_result_conflict_with_version, version)
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        else -> return
    }

    val screenPadding = dimensionResource(id = R.dimen.resource_detail_tab_content_padding)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = screenPadding),
        colors = CardDefaults.cardColors(containerColor = status.containerColor),
    ) {
        Text(
            text = status.message,
            color = status.contentColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(screenPadding),
        )
    }
}

private data class EditorStatus(
    val message: String,
    val containerColor: androidx.compose.ui.graphics.Color,
    val contentColor: androidx.compose.ui.graphics.Color,
)

@Composable
private fun integerResourceCompat(id: Int): Int = androidx.compose.ui.res.integerResource(id = id)
