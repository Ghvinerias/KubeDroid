package com.kubedroid.feature.pods.portforward.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PortForwardRoute(
    request: PortForwardRouteRequest,
    viewModel: PortForwardViewModel,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(request) {
        viewModel.onIntent(
            PortForwardIntent.Initialize(
                apiServer = request.apiServer,
                namespace = request.namespace,
                bearerToken = request.bearerToken,
                pods = request.pods,
            ),
        )
    }

    LaunchedEffect(viewModel, context) {
        viewModel.events.collect { event ->
            when (event) {
                is PortForwardUiEvent.CopyUrl -> {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText(event.url, event.url),
                    )
                }

                is PortForwardUiEvent.OpenInBrowser -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                    runCatching { context.startActivity(intent) }
                }
            }
        }
    }

    PortForwardScreen(
        sessions = state.sessions,
        isStartSheetVisible = state.isStartSheetVisible,
        startSheetState = state.startSheet,
        onBackClick = onBackClick,
        onOpenStartSheetClick = { viewModel.onIntent(PortForwardIntent.OpenStartSheet) },
        onDismissStartSheet = { viewModel.onIntent(PortForwardIntent.DismissStartSheet) },
        onPodSelected = { viewModel.onIntent(PortForwardIntent.SelectPod(it)) },
        onLocalPortInputChange = { viewModel.onIntent(PortForwardIntent.UpdateLocalPort(it)) },
        onRemotePortInputChange = { viewModel.onIntent(PortForwardIntent.UpdateRemotePort(it)) },
        onStartPortForwardClick = { viewModel.onIntent(PortForwardIntent.StartPortForward) },
        onCloseSessionClick = { viewModel.onIntent(PortForwardIntent.CloseSession(it)) },
        onCopyUrlClick = { viewModel.onIntent(PortForwardIntent.CopyUrl(it)) },
        onOpenInBrowserClick = { viewModel.onIntent(PortForwardIntent.OpenInBrowser(it)) },
    )
}
