package com.kubedroid.feature.crd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.core.network.resources.YamlEditResult
import com.kubedroid.feature.crd.R
import com.kubedroid.feature.crd.model.CustomResourceDefinition

@Composable
fun CrdResourceDetailRoute(
    crd: CustomResourceDefinition,
    name: String,
    namespace: String,
    viewModel: CrdResourceDetailViewModel,
    onBackClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(crd.key(), name, namespace) {
        viewModel.onIntent(
            CrdResourceDetailIntent.BindTarget(
                crd = crd,
                name = name,
                namespace = namespace,
            ),
        )
    }

    CrdResourceDetailScreen(
        state = state,
        onBackClick = onBackClick,
        onRetryClick = { viewModel.onIntent(CrdResourceDetailIntent.Retry) },
        onEditClick = { viewModel.onIntent(CrdResourceDetailIntent.StartEditing) },
        onCancelEditClick = { viewModel.onIntent(CrdResourceDetailIntent.CancelEditing) },
        onYamlDraftChange = { viewModel.onIntent(CrdResourceDetailIntent.YamlDraftChanged(it)) },
        onApplyClick = { viewModel.onIntent(CrdResourceDetailIntent.ApplyYaml) },
        onDismissUnknownTypeDialog = {
            viewModel.onIntent(CrdResourceDetailIntent.DismissUnknownTypeConfirmation)
        },
        onConfirmUnknownTypeDialog = {
            viewModel.onIntent(CrdResourceDetailIntent.ConfirmUnknownTypeEditing)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrdResourceDetailScreen(
    state: CrdResourceDetailScreenState,
    onBackClick: () -> Unit,
    onRetryClick: () -> Unit,
    onEditClick: () -> Unit,
    onCancelEditClick: () -> Unit,
    onYamlDraftChange: (String) -> Unit,
    onApplyClick: () -> Unit,
    onDismissUnknownTypeDialog: () -> Unit,
    onConfirmUnknownTypeDialog: () -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.crd_screen_padding)
    val sectionSpacing = dimensionResource(id = R.dimen.crd_section_spacing)

    val title = when (val contentState = state.state) {
        is CrdResourceDetailContentState.Content -> stringResource(
            id = R.string.crd_detail_title_format,
            contentState.crd.kind,
            contentState.name,
        )

        else -> stringResource(id = R.string.crd_detail_title)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.crd_detail_back_cd),
                        )
                    }
                },
                actions = {
                    val content = state.state as? CrdResourceDetailContentState.Content
                    if (content != null) {
                        if (content.isEditing) {
                            TextButton(onClick = onCancelEditClick) {
                                Text(text = stringResource(id = R.string.crd_detail_cancel_edit))
                            }
                            Button(
                                onClick = onApplyClick,
                                enabled = !content.isApplying,
                            ) {
                                Text(text = stringResource(id = R.string.crd_detail_apply_action))
                            }
                        } else {
                            IconButton(onClick = onEditClick) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(id = R.string.crd_detail_edit_cd),
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val contentState = state.state) {
            CrdResourceDetailContentState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is CrdResourceDetailContentState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = screenPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = contentState.cause?.localizedMessage
                            ?: stringResource(id = R.string.crd_detail_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetryClick) {
                        Text(text = stringResource(id = R.string.crd_detail_retry))
                    }
                }
            }

            is CrdResourceDetailContentState.Content -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = screenPadding),
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                ) {
                    ResourceMetaRow(
                        namespace = contentState.namespace.ifBlank {
                            stringResource(id = R.string.crd_detail_cluster_scoped)
                        },
                        apiVersion = "${contentState.crd.group}/${contentState.crd.version}",
                    )

                    ApplyStatusCard(
                        result = contentState.lastApplyResult,
                        applyError = contentState.applyError,
                        isApplying = contentState.isApplying,
                    )

                    if (contentState.isEditing) {
                        OutlinedTextField(
                            value = contentState.yamlDraft,
                            onValueChange = onYamlDraftChange,
                            enabled = !contentState.isApplying,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            label = { Text(text = stringResource(id = R.string.crd_detail_yaml_label)) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            maxLines = Int.MAX_VALUE,
                        )
                    } else {
                        YamlViewer(
                            yaml = contentState.yaml,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                }
            }
        }
    }

    if (state.isUnknownTypeConfirmationVisible) {
        AlertDialog(
            onDismissRequest = onDismissUnknownTypeDialog,
            title = { Text(text = stringResource(id = R.string.crd_detail_unknown_confirm_title)) },
            text = { Text(text = stringResource(id = R.string.crd_detail_unknown_confirm_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmUnknownTypeDialog) {
                    Text(text = stringResource(id = R.string.crd_detail_unknown_confirm_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissUnknownTypeDialog) {
                    Text(text = stringResource(id = R.string.crd_detail_unknown_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun ResourceMetaRow(
    namespace: String,
    apiVersion: String,
) {
    val spacing = dimensionResource(id = R.dimen.crd_section_spacing)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Text(
            text = stringResource(id = R.string.crd_detail_namespace_format, namespace),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(id = R.string.crd_detail_api_version_format, apiVersion),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ApplyStatusCard(
    result: YamlEditResult?,
    applyError: Throwable?,
    isApplying: Boolean,
) {
    if (!isApplying && result == null && applyError == null) return

    val padding = dimensionResource(id = R.dimen.crd_status_card_padding)
    val (text, background, textColor) = when {
        isApplying -> Triple(
            stringResource(id = R.string.crd_detail_apply_in_progress),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        applyError != null -> Triple(
            applyError.localizedMessage ?: stringResource(id = R.string.crd_detail_apply_error),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )

        result is YamlEditResult.Applied -> Triple(
            if (result.resourceVersion.isNullOrBlank()) {
                stringResource(id = R.string.crd_detail_apply_success)
            } else {
                val version = result.resourceVersion.orEmpty()
                stringResource(id = R.string.crd_detail_apply_success_with_version, version)
            },
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )

        result is YamlEditResult.NoChanges -> Triple(
            stringResource(id = R.string.crd_detail_apply_no_changes),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        result is YamlEditResult.Conflict -> Triple(
            if (result.currentResourceVersion.isNullOrBlank()) {
                stringResource(id = R.string.crd_detail_apply_conflict)
            } else {
                val version = result.currentResourceVersion.orEmpty()
                stringResource(
                    id = R.string.crd_detail_apply_conflict_with_version,
                    version,
                )
            },
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )

        else -> return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, shape = MaterialTheme.shapes.small)
            .padding(padding),
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun YamlViewer(
    yaml: String,
    modifier: Modifier = Modifier,
) {
    val lines = if (yaml.isBlank()) emptyList() else yaml.lines()
    val rowPadding = dimensionResource(id = R.dimen.crd_yaml_row_padding)

    if (lines.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(id = R.string.crd_detail_yaml_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = MaterialTheme.shapes.small,
            ),
    ) {
        itemsIndexed(lines) { index, line ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = rowPadding, vertical = 4.dp),
            ) {
                Text(
                    text = (index + 1).toString(),
                    modifier = Modifier.width(40.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
