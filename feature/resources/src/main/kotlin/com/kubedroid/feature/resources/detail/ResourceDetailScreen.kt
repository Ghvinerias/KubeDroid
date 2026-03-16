package com.kubedroid.feature.resources.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.kubedroid.feature.resources.R

enum class ResourceDetailTab {
    YAML,
    DESCRIBE,
    EVENTS,
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ResourceDetailScreen(
    state: ResourceDetailUiState,
    selectedTab: ResourceDetailTab,
    onTabSelected: (ResourceDetailTab) -> Unit,
    onEditYamlClick: (String) -> Unit,
    onLogsClick: () -> Unit = {},
    onExecClick: () -> Unit = {},
    onPortForwardClick: () -> Unit = {},
    onBackClick: () -> Unit,
    onRetryClick: () -> Unit,
    onRefreshClick: () -> Unit = onRetryClick,
    modifier: Modifier = Modifier,
) {
    val screenPadding = dimensionResource(id = R.dimen.resource_detail_screen_padding)
    val contentSpacing = dimensionResource(id = R.dimen.resource_detail_content_spacing)
    var isShortcutHelpVisible by rememberSaveable { mutableStateOf(false) }

    val title = when (state) {
        is ResourceDetailUiState.Content -> stringResource(
            id = R.string.resource_detail_title_format,
            state.detail.kind,
            state.detail.name,
        )

        else -> stringResource(id = R.string.resource_detail_title)
    }

    val shortcutModifier = Modifier.onPreviewKeyEvent { keyEvent ->
        if (keyEvent.type != KeyEventType.KeyUp) {
            return@onPreviewKeyEvent false
        }
        val rawChar = keyEvent.nativeKeyEvent.unicodeChar
        if (rawChar == 0) {
            return@onPreviewKeyEvent false
        }
        val pressed = rawChar.toChar()
        when {
            pressed == '?' -> {
                isShortcutHelpVisible = true
                true
            }
            pressed.equals('d', ignoreCase = true) -> {
                onTabSelected(ResourceDetailTab.DESCRIBE)
                true
            }
            pressed.equals('l', ignoreCase = true) -> {
                onLogsClick()
                true
            }
            pressed.equals('e', ignoreCase = true) -> {
                if (state is ResourceDetailUiState.Content) {
                    onEditYamlClick(state.yamlDraft)
                }
                true
            }
            pressed.equals('s', ignoreCase = true) -> {
                onExecClick()
                true
            }
            pressed.equals('r', ignoreCase = true) -> {
                onRefreshClick()
                true
            }
            else -> false
        }
    }

    Scaffold(
        modifier = modifier.then(shortcutModifier),
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.resource_detail_back_cd),
                        )
                    }
                },
                actions = {
                    if (state is ResourceDetailUiState.Content) {
                        IconButton(onClick = onLogsClick) {
                            Icon(
                                imageVector = Icons.Default.ViewList,
                                contentDescription = stringResource(id = R.string.resource_detail_logs_cd),
                            )
                        }
                        IconButton(onClick = onExecClick) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = stringResource(id = R.string.resource_detail_exec_cd),
                            )
                        }
                        IconButton(onClick = onPortForwardClick) {
                            Icon(
                                imageVector = Icons.Default.Lan,
                                contentDescription = stringResource(id = R.string.resource_detail_port_forward_cd),
                            )
                        }
                        IconButton(onClick = { onEditYamlClick(state.yamlDraft) }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(id = R.string.resource_detail_edit_yaml_cd),
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        when (state) {
            ResourceDetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            ResourceDetailUiState.NotFound -> {
                StateMessage(
                    text = stringResource(id = R.string.resource_detail_not_found),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = screenPadding),
                )
            }

            is ResourceDetailUiState.Error -> {
                StateMessage(
                    text = state.cause?.localizedMessage
                        ?: stringResource(id = R.string.resource_detail_error),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = screenPadding),
                    onRetryClick = onRetryClick,
                )
            }

            is ResourceDetailUiState.Content -> {
                val tabs = listOf(
                    ResourceDetailTab.YAML to stringResource(id = R.string.resource_detail_tab_yaml),
                    ResourceDetailTab.DESCRIBE to stringResource(id = R.string.resource_detail_tab_describe),
                    ResourceDetailTab.EVENTS to stringResource(id = R.string.resource_detail_tab_events),
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing),
                ) {
                    TabRow(selectedTabIndex = tabs.indexOfFirst { it.first == selectedTab }) {
                        tabs.forEach { (tab, label) ->
                            Tab(
                                selected = tab == selectedTab,
                                onClick = { onTabSelected(tab) },
                                text = { Text(text = label) },
                            )
                        }
                    }

                    when (selectedTab) {
                        ResourceDetailTab.YAML -> {
                            YamlViewerTab(
                                yaml = state.detail.yaml,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = screenPadding),
                            )
                        }

                        ResourceDetailTab.DESCRIBE -> {
                            DescribeTab(
                                detail = state.detail,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = screenPadding),
                            )
                        }

                        ResourceDetailTab.EVENTS -> {
                            EventsTab(
                                events = state.detail.events,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = screenPadding),
                            )
                        }
                    }
                }
            }
        }
    }

    if (isShortcutHelpVisible) {
        ShortcutHelpDialog(onDismissRequest = { isShortcutHelpVisible = false })
    }
}

@Composable
private fun StateMessage(
    text: String,
    modifier: Modifier,
    onRetryClick: (() -> Unit)? = null,
) {
    val contentSpacing = dimensionResource(id = R.dimen.resource_detail_content_spacing)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onRetryClick == null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        if (onRetryClick != null) {
            TextButton(
                modifier = Modifier.padding(top = contentSpacing),
                onClick = onRetryClick,
            ) {
                Text(text = stringResource(id = R.string.resource_detail_retry))
            }
        }
    }
}

@Composable
private fun ShortcutHelpDialog(
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(id = R.string.resource_detail_shortcuts_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.resource_detail_content_spacing))) {
                Text(text = stringResource(id = R.string.resource_detail_shortcut_describe))
                Text(text = stringResource(id = R.string.resource_detail_shortcut_logs))
                Text(text = stringResource(id = R.string.resource_detail_shortcut_edit))
                Text(text = stringResource(id = R.string.resource_detail_shortcut_shell))
                Text(text = stringResource(id = R.string.resource_detail_shortcut_refresh))
                Text(text = stringResource(id = R.string.resource_detail_shortcut_help))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(id = R.string.resource_detail_shortcuts_close))
            }
        },
    )
}
