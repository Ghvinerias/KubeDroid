package com.kubedroid.feature.pods.portforward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.kubedroid.feature.pods.R

data class PortForwardSessionUiModel(
    val id: String,
    val namespace: String,
    val podName: String,
    val localPort: Int,
    val remotePort: Int,
    val status: PortForwardSessionUiStatus,
)

enum class PortForwardSessionUiStatus {
    STARTING,
    ACTIVE,
    FAILED,
    CLOSED,
}

data class StartPortForwardSheetUiState(
    val pods: List<String> = emptyList(),
    val selectedPod: String? = null,
    val localPortInput: String = "",
    val remotePortInput: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortForwardScreen(
    sessions: List<PortForwardSessionUiModel>,
    isStartSheetVisible: Boolean,
    startSheetState: StartPortForwardSheetUiState,
    onBackClick: () -> Unit,
    onOpenStartSheetClick: () -> Unit,
    onDismissStartSheet: () -> Unit,
    onPodSelected: (String) -> Unit,
    onLocalPortInputChange: (String) -> Unit,
    onRemotePortInputChange: (String) -> Unit,
    onStartPortForwardClick: () -> Unit,
    onCloseSessionClick: (String) -> Unit,
    onCopyUrlClick: (String) -> Unit,
    onOpenInBrowserClick: (String) -> Unit,
) {
    val screenPadding = dimensionResource(id = R.dimen.port_forward_screen_padding)
    val listSpacing = dimensionResource(id = R.dimen.port_forward_item_spacing)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.port_forward_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.port_forward_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenStartSheetClick) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(id = R.string.port_forward_start_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(screenPadding),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(id = R.string.port_forward_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = screenPadding),
                verticalArrangement = Arrangement.spacedBy(listSpacing),
            ) {
                items(items = sessions, key = { it.id }) { session ->
                    PortForwardSessionRow(
                        session = session,
                        onCloseSessionClick = { onCloseSessionClick(session.id) },
                        onCopyUrlClick = onCopyUrlClick,
                        onOpenInBrowserClick = onOpenInBrowserClick,
                    )
                }
            }
        }
    }

    if (isStartSheetVisible) {
        StartPortForwardSheet(
            state = startSheetState,
            onDismissRequest = onDismissStartSheet,
            onPodSelected = onPodSelected,
            onLocalPortInputChange = onLocalPortInputChange,
            onRemotePortInputChange = onRemotePortInputChange,
            onStartClick = onStartPortForwardClick,
        )
    }
}

@Composable
private fun PortForwardSessionRow(
    session: PortForwardSessionUiModel,
    onCloseSessionClick: () -> Unit,
    onCopyUrlClick: (String) -> Unit,
    onOpenInBrowserClick: (String) -> Unit,
) {
    val cardPadding = dimensionResource(id = R.dimen.port_forward_item_padding)
    val contentSpacing = dimensionResource(id = R.dimen.port_forward_item_spacing)
    val actionSpacing = dimensionResource(id = R.dimen.port_forward_action_spacing)
    val url = stringResource(id = R.string.port_forward_local_url_template, session.localPort)

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.podName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            id = R.string.port_forward_session_subtitle,
                            session.namespace,
                            session.remotePort,
                            session.localPort,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onCloseSessionClick) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(id = R.string.port_forward_close_session_cd),
                    )
                }
            }

            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(text = stringResource(id = session.status.labelResId())) },
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )

            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(actionSpacing),
            ) {
                TextButton(onClick = { onCopyUrlClick(url) }) {
                    Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null)
                    Text(text = stringResource(id = R.string.port_forward_copy_url))
                }
                TextButton(onClick = { onOpenInBrowserClick(url) }) {
                    Icon(imageVector = Icons.Filled.OpenInBrowser, contentDescription = null)
                    Text(text = stringResource(id = R.string.port_forward_open_in_browser))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartPortForwardSheet(
    state: StartPortForwardSheetUiState,
    onDismissRequest: () -> Unit,
    onPodSelected: (String) -> Unit,
    onLocalPortInputChange: (String) -> Unit,
    onRemotePortInputChange: (String) -> Unit,
    onStartClick: () -> Unit,
) {
    val sheetPadding = dimensionResource(id = R.dimen.port_forward_sheet_padding)
    val contentSpacing = dimensionResource(id = R.dimen.port_forward_item_spacing)

    var podsExpanded by remember { mutableStateOf(false) }

    val remotePort = state.remotePortInput.toIntOrNull()
    val localPort = state.localPortInput.toIntOrNull()

    val isRemotePortValid = remotePort != null && remotePort in 1..65535
    val isLocalPortValid = state.localPortInput.isBlank() || (localPort != null && localPort in 0..65535)
    val canStart = state.selectedPod != null && isRemotePortValid && isLocalPortValid

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sheetPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            Text(
                text = stringResource(id = R.string.port_forward_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(id = R.string.port_forward_sheet_pod_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { if (state.pods.isNotEmpty()) podsExpanded = true },
                ) {
                    Text(
                        text = state.selectedPod
                            ?: stringResource(id = R.string.port_forward_sheet_pod_placeholder),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                DropdownMenu(
                    expanded = podsExpanded,
                    onDismissRequest = { podsExpanded = false },
                ) {
                    state.pods.forEach { pod ->
                        DropdownMenuItem(
                            text = { Text(text = pod) },
                            onClick = {
                                podsExpanded = false
                                onPodSelected(pod)
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.remotePortInput,
                onValueChange = { onRemotePortInputChange(it.filter(Char::isDigit)) },
                label = { Text(text = stringResource(id = R.string.port_forward_sheet_remote_port_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.remotePortInput.isNotBlank() && !isRemotePortValid,
                supportingText = {
                    if (state.remotePortInput.isBlank() || isRemotePortValid) {
                        Text(text = stringResource(id = R.string.port_forward_sheet_remote_port_required))
                    } else {
                        Text(text = stringResource(id = R.string.port_forward_sheet_port_invalid))
                    }
                },
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.localPortInput,
                onValueChange = { onLocalPortInputChange(it.filter(Char::isDigit)) },
                label = { Text(text = stringResource(id = R.string.port_forward_sheet_local_port_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.localPortInput.isNotBlank() && !isLocalPortValid,
                supportingText = {
                    if (state.localPortInput.isBlank() || isLocalPortValid) {
                        Text(text = stringResource(id = R.string.port_forward_sheet_local_port_supporting))
                    } else {
                        Text(text = stringResource(id = R.string.port_forward_sheet_port_invalid))
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(id = R.string.port_forward_sheet_cancel))
                }
                TextButton(
                    enabled = canStart,
                    onClick = onStartClick,
                ) {
                    Text(text = stringResource(id = R.string.port_forward_sheet_start))
                }
            }
        }
    }
}

private fun PortForwardSessionUiStatus.labelResId(): Int = when (this) {
    PortForwardSessionUiStatus.STARTING -> R.string.port_forward_session_status_starting
    PortForwardSessionUiStatus.ACTIVE -> R.string.port_forward_session_status_active
    PortForwardSessionUiStatus.FAILED -> R.string.port_forward_session_status_failed
    PortForwardSessionUiStatus.CLOSED -> R.string.port_forward_session_status_closed
}

@Preview(showBackground = true)
@Composable
private fun PortForwardScreenPreview() {
    MaterialTheme {
        PortForwardScreen(
            sessions = listOf(
                PortForwardSessionUiModel(
                    id = "1",
                    namespace = "default",
                    podName = "nginx-6f78c89d7-7g2bl",
                    localPort = 8080,
                    remotePort = 80,
                    status = PortForwardSessionUiStatus.ACTIVE,
                ),
            ),
            isStartSheetVisible = false,
            startSheetState = StartPortForwardSheetUiState(),
            onBackClick = {},
            onOpenStartSheetClick = {},
            onDismissStartSheet = {},
            onPodSelected = {},
            onLocalPortInputChange = {},
            onRemotePortInputChange = {},
            onStartPortForwardClick = {},
            onCloseSessionClick = {},
            onCopyUrlClick = {},
            onOpenInBrowserClick = {},
        )
    }
}
