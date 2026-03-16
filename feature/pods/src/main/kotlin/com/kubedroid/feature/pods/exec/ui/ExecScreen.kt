package com.kubedroid.feature.pods.exec.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kubedroid.feature.pods.exec.PodExecRequest
import com.kubedroid.feature.pods.R
import org.json.JSONObject

@Composable
fun ExecRoute(
    request: PodExecRequest,
    viewModel: ExecViewModel,
    onBackClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(request) {
        viewModel.onIntent(ExecIntent.Connect(request))
    }

    LaunchedEffect(viewModel, webView) {
        viewModel.terminalEvents.collect { event ->
            val terminalView = webView ?: return@collect
            when (event) {
                is ExecTerminalEvent.Stdout -> {
                    terminalView.safeEval("window.KubeDroidTerminal.write(${JSONObject.quote(event.text)});")
                }

                is ExecTerminalEvent.Stderr -> {
                    terminalView.safeEval("window.KubeDroidTerminal.writeError(${JSONObject.quote(event.text)});")
                }

                ExecTerminalEvent.Clear -> {
                    terminalView.safeEval("window.KubeDroidTerminal.clear();")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onIntent(ExecIntent.Disconnect) }
    }

    ExecScreen(
        state = state,
        onBackClick = onBackClick,
        onDisconnectClick = { viewModel.onIntent(ExecIntent.Disconnect) },
        onTerminalInput = { viewModel.onIntent(ExecIntent.SendInput(it)) },
        onTerminalResize = { cols, rows ->
            viewModel.onIntent(ExecIntent.Resize(columns = cols, rows = rows))
        },
        onWebViewReady = { webView = it },
        onWebViewReleased = { released ->
            if (webView == released) {
                webView = null
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExecScreen(
    state: ExecUiState,
    onBackClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onTerminalInput: (String) -> Unit,
    onTerminalResize: (Int, Int) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onWebViewReleased: (WebView) -> Unit,
    terminalContent: @Composable (Modifier) -> Unit = { modifier ->
        ExecTerminalWebView(
            context = LocalContext.current,
            onTerminalInput = onTerminalInput,
            onTerminalResize = onTerminalResize,
            onWebViewReady = onWebViewReady,
            onWebViewReleased = onWebViewReleased,
            modifier = modifier,
        )
    },
) {
    val statusHorizontalPadding = dimensionResource(id = R.dimen.exec_screen_status_padding)
    val statusVerticalPadding = dimensionResource(id = R.dimen.exec_screen_status_vertical_padding)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.exec_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.exec_screen_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onDisconnectClick) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.exec_screen_disconnect_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            terminalContent(
                Modifier
                    .fillMaxSize()
                    .weight(1f),
            )

            val statusText = when (state) {
                ExecUiState.Idle -> stringResource(R.string.exec_screen_state_idle)
                ExecUiState.Connecting -> stringResource(R.string.exec_screen_state_connecting)
                ExecUiState.Active -> stringResource(R.string.exec_screen_state_active)
                is ExecUiState.Exited -> stringResource(R.string.exec_screen_state_exited, state.exitCode)
                is ExecUiState.Error -> stringResource(
                    R.string.exec_screen_state_error,
                    state.message ?: stringResource(R.string.exec_screen_state_error_unknown),
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = statusHorizontalPadding,
                    vertical = statusVerticalPadding,
                ),
            )
        }
    }
}

@Composable
@SuppressLint("SetJavaScriptEnabled")
private fun ExecTerminalWebView(
    context: Context,
    onTerminalInput: (String) -> Unit,
    onTerminalResize: (Int, Int) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onWebViewReleased: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()

                val keyboardRequester: () -> Unit = {
                    requestFocus()
                    showSoftKeyboard()
                }

                addJavascriptInterface(
                    ExecJavascriptBridge(
                        onTerminalInput = onTerminalInput,
                        onTerminalResize = onTerminalResize,
                        onTerminalReady = keyboardRequester,
                        onTerminalTapped = keyboardRequester,
                    ),
                    JS_BRIDGE_NAME,
                )

                setOnTouchListener { _, _ ->
                    keyboardRequester()
                    false
                }

                loadUrl(TERMINAL_ASSET_URL)
                onWebViewReady(this)
            }
        },
        onRelease = { releasedWebView ->
            releasedWebView.removeJavascriptInterface(JS_BRIDGE_NAME)
            releasedWebView.stopLoading()
            releasedWebView.destroy()
            onWebViewReleased(releasedWebView)
        },
    )
}

internal class ExecJavascriptBridge(
    private val onTerminalInput: (String) -> Unit,
    private val onTerminalResize: (Int, Int) -> Unit,
    private val onTerminalReady: () -> Unit,
    private val onTerminalTapped: () -> Unit,
) {
    @JavascriptInterface
    fun onInput(text: String) {
        onTerminalInput(text)
    }

    @JavascriptInterface
    fun onResize(columns: Int, rows: Int) {
        onTerminalResize(columns, rows)
    }

    @JavascriptInterface
    fun onReady() {
        onTerminalReady()
    }

    @JavascriptInterface
    fun onTap() {
        onTerminalTapped()
    }
}

private fun WebView.safeEval(script: String) {
    post { evaluateJavascript(script, null) }
}

private fun View.showSoftKeyboard() {
    val imm = context.getSystemService(InputMethodManager::class.java)
    imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}

private const val JS_BRIDGE_NAME = "KubeDroidBridge"
private const val TERMINAL_ASSET_URL = "file:///android_asset/terminal/index.html"
