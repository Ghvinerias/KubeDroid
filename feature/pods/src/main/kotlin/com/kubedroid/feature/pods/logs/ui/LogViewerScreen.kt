package com.kubedroid.feature.pods.logs.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString.Builder
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kubedroid.feature.pods.R
import com.kubedroid.feature.pods.logs.LogLine
import com.kubedroid.feature.pods.logs.LogRepository
import com.kubedroid.feature.pods.logs.LogStreamError
import com.kubedroid.feature.pods.logs.PodLogRequest
import com.kubedroid.feature.pods.logs.impl.FeatureLogStreamException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_BUFFER_LINES = 10_000

sealed interface LogViewerIntent {
    data class Start(val request: PodLogRequest) : LogViewerIntent
    data object Stop : LogViewerIntent
    data object Retry : LogViewerIntent
    data class SetSearchQuery(val query: String) : LogViewerIntent
    data class SelectContainer(val container: String?) : LogViewerIntent
    data class SetFollowEnabled(val enabled: Boolean) : LogViewerIntent
    data class SetTimestampEnabled(val enabled: Boolean) : LogViewerIntent
}

@HiltViewModel
class LogViewerViewModel @Inject constructor(
    private val logRepository: LogRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogViewerUiState())
    val uiState: StateFlow<LogViewerUiState> = _uiState.asStateFlow()

    private var currentRequest: PodLogRequest? = null
    private var streamJob: Job? = null

    fun onIntent(intent: LogViewerIntent) {
        when (intent) {
            is LogViewerIntent.Start -> start(intent.request)
            LogViewerIntent.Stop -> stop()
            LogViewerIntent.Retry -> currentRequest?.let(::start)
            is LogViewerIntent.SetSearchQuery -> updateDerivedState {
                it.copy(searchQuery = intent.query)
            }

            is LogViewerIntent.SelectContainer -> updateDerivedState {
                it.copy(selectedContainer = intent.container)
            }

            is LogViewerIntent.SetFollowEnabled -> updateDerivedState {
                it.copy(isFollowEnabled = intent.enabled)
            }

            is LogViewerIntent.SetTimestampEnabled -> updateDerivedState {
                it.copy(isTimestampEnabled = intent.enabled)
            }
        }
    }

    private fun start(request: PodLogRequest) {
        streamJob?.cancel()
        currentRequest = request
        _uiState.update {
            LogViewerUiState(
                isLoading = true,
                streamId = request.streamId,
                isFollowEnabled = true,
                isTimestampEnabled = true,
            )
        }

        streamJob = viewModelScope.launch {
            logRepository.streamPodLogs(request).collect { result ->
                result.fold(
                    onSuccess = { line ->
                        updateDerivedState { current ->
                            val updatedLines = if (line.message.isBlank()) {
                                current.lines
                            } else {
                                (current.lines + line).takeLast(MAX_BUFFER_LINES)
                            }
                            current.copy(
                                isLoading = false,
                                lines = updatedLines,
                                errorResId = null,
                                errorMessage = null,
                            )
                        }
                    },
                    onFailure = { throwable ->
                        updateDerivedState {
                            it.copy(
                                isLoading = false,
                                errorResId = R.string.log_viewer_error_generic,
                                errorMessage = throwable.toLogViewerErrorMessage(),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun stop() {
        val streamId = _uiState.value.streamId
        streamJob?.cancel()
        streamJob = null
        if (streamId.isNotBlank()) {
            viewModelScope.launch {
                logRepository.stopStream(streamId)
            }
        }
    }

    private fun updateDerivedState(transform: (LogViewerUiState) -> LogViewerUiState) {
        _uiState.update { current ->
            val changed = transform(current)
            val containers = changed.lines
                .map { it.streamId }
                .filter { it.isNotBlank() }
                .distinct()
            val selectedContainer = changed.selectedContainer?.takeIf { selected ->
                selected in containers
            }
            val visibleLines = changed.lines
                .asSequence()
                .filter { line -> selectedContainer == null || line.streamId == selectedContainer }
                .filter { line ->
                    changed.searchQuery.isBlank() || line.message.contains(
                        changed.searchQuery,
                        ignoreCase = true,
                    )
                }
                .toList()

            changed.copy(
                containers = containers,
                selectedContainer = selectedContainer,
                visibleLines = visibleLines,
                isEmpty = !changed.isLoading && changed.errorResId == null && visibleLines.isEmpty(),
            )
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

@Composable
fun LogViewerRoute(
    request: PodLogRequest,
    viewModel: LogViewerViewModel,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(request) {
        viewModel.onIntent(LogViewerIntent.Start(request))
    }

    LogViewerScreen(
        state = uiState,
        onBackClick = onBackClick,
        onStopClick = { viewModel.onIntent(LogViewerIntent.Stop) },
        onRetryClick = { viewModel.onIntent(LogViewerIntent.Retry) },
        onSearchQueryChange = { viewModel.onIntent(LogViewerIntent.SetSearchQuery(it)) },
        onContainerSelected = { viewModel.onIntent(LogViewerIntent.SelectContainer(it)) },
        onFollowEnabledChange = { viewModel.onIntent(LogViewerIntent.SetFollowEnabled(it)) },
        onTimestampEnabledChange = { viewModel.onIntent(LogViewerIntent.SetTimestampEnabled(it)) },
        onShareClick = { text ->
            if (text.isBlank()) {
                return@LogViewerScreen
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(
                Intent.createChooser(
                    shareIntent,
                    context.getString(R.string.log_viewer_share_title),
                ),
            )
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LogViewerScreen(
    state: LogViewerUiState,
    onBackClick: () -> Unit,
    onStopClick: () -> Unit,
    onRetryClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onContainerSelected: (String?) -> Unit,
    onFollowEnabledChange: (Boolean) -> Unit,
    onTimestampEnabledChange: (Boolean) -> Unit,
    onShareClick: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.isFollowEnabled, state.visibleLines.size) {
        if (state.isFollowEnabled && state.visibleLines.isNotEmpty()) {
            listState.scrollToItem(state.visibleLines.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = stringResource(id = R.string.log_viewer_title))
                        if (state.isFollowEnabled) {
                            val followIndicatorColor = MaterialTheme.colorScheme.primary
                            Canvas(modifier = Modifier.size(7.dp)) {
                                drawCircle(color = followIndicatorColor)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.log_viewer_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = state.visibleLines.isNotEmpty(),
                        onClick = { onShareClick(state.visibleLogText()) },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(id = R.string.log_viewer_share_cd),
                        )
                    }
                    IconButton(onClick = onStopClick) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(id = R.string.log_viewer_stop_cd),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        when {
            state.isLoading && state.lines.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.errorResId != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.errorMessage ?: stringResource(id = state.errorResId),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row {
                        IconButton(onClick = onRetryClick) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(id = R.string.log_viewer_retry_cd),
                            )
                        }
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(paddingValues),
                ) {
                    LogSearchBar(
                        query = state.searchQuery,
                        onQueryChange = onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    val tabs = listOf<String?>(null) + state.containers
                    ScrollableTabRow(selectedTabIndex = tabs.indexOf(state.selectedContainer).coerceAtLeast(0)) {
                        tabs.forEach { container ->
                            val isSelected = container == state.selectedContainer
                            Tab(
                                selected = isSelected,
                                onClick = { onContainerSelected(container) },
                                text = {
                                    Text(
                                        text = container ?: stringResource(R.string.log_viewer_container_all),
                                    )
                                },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.isFollowEnabled,
                            onClick = { onFollowEnabledChange(!state.isFollowEnabled) },
                            label = { Text(text = stringResource(R.string.log_viewer_follow_toggle)) },
                        )
                        FilterChip(
                            selected = state.isTimestampEnabled,
                            onClick = { onTimestampEnabledChange(!state.isTimestampEnabled) },
                            label = { Text(text = stringResource(R.string.log_viewer_timestamps_toggle)) },
                        )
                    }

                    if (state.isEmpty) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(id = R.string.log_viewer_empty),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                        ) {
                            items(
                                items = state.visibleLines,
                                key = { "${it.streamId}-${it.timestamp}-${it.message.hashCode()}" },
                            ) { line ->
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (line.isError) {
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                                            } else {
                                                Color.Transparent
                                            },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 2.dp),
                                    text = buildLogLineText(
                                        line = line,
                                        showTimestamp = state.isTimestampEnabled,
                                        query = state.searchQuery,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun buildLogLineText(
    line: LogLine,
    showTimestamp: Boolean,
    query: String,
): AnnotatedString {
    val baseMessage = if (showTimestamp && line.timestamp != null) {
        "${line.timestamp} ${line.message}"
    } else {
        line.message
    }
    if (query.isBlank()) return AnnotatedString(baseMessage)

    val lowerMessage = baseMessage.lowercase()
    val lowerQuery = query.lowercase()
    val builder = Builder(baseMessage)
    if (showTimestamp && line.timestamp != null) {
        val end = line.timestamp.length.coerceAtMost(baseMessage.length)
        builder.addStyle(
            SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant),
            start = 0,
            end = end,
        )
    }
    var start = 0
    while (start < lowerMessage.length) {
        val index = lowerMessage.indexOf(lowerQuery, startIndex = start)
        if (index == -1) break
        builder.addStyle(
            SpanStyle(background = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
            start = index,
            end = index + lowerQuery.length,
        )
        start = index + lowerQuery.length
    }
    return builder.toAnnotatedString()
}

private fun Throwable.toLogViewerErrorMessage(): String {
    val typed = this as? FeatureLogStreamException
    return when (val error = typed?.error) {
        LogStreamError.Unauthorized -> "Unauthorized to stream pod logs (401)"
        LogStreamError.Forbidden -> "Forbidden from streaming pod logs (403)"
        LogStreamError.NotFound -> "Pod log endpoint not found (404)"
        LogStreamError.Timeout -> "Pod log stream timed out"
        LogStreamError.NetworkInterrupted -> "Network interrupted while streaming logs"
        LogStreamError.ServerError -> "Cluster API server error while streaming logs (500)"
        is LogStreamError.TlsError -> "TLS error while opening pod logs: ${error.detail.orEmpty().ifBlank { "unknown TLS failure" }}"
        is LogStreamError.EofError -> "Log stream closed unexpectedly: ${error.detail.orEmpty().ifBlank { "EOF" }}"
        is LogStreamError.Unknown -> "Unable to stream pod logs: ${error.detail.orEmpty().ifBlank { "unknown error" }}"
        null -> message ?: "Unable to stream pod logs"
    }
}

@Composable
fun LogSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        placeholder = {
            Text(text = stringResource(R.string.log_viewer_search_hint))
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.log_viewer_search_clear_cd),
                    )
                }
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun LogViewerScreenPreviewLight() {
    MaterialTheme {
        LogViewerScreen(
            state = LogViewerUiState(
                isLoading = false,
                lines = listOf(
                    LogLine("frontend", "2026-01-01T00:00:00Z", "line one", LogLine.Source.STDOUT),
                    LogLine("backend", "2026-01-01T00:00:01Z", "line two", LogLine.Source.STDOUT),
                ),
                visibleLines = listOf(
                    LogLine("frontend", "2026-01-01T00:00:00Z", "line one", LogLine.Source.STDOUT),
                    LogLine("backend", "2026-01-01T00:00:01Z", "line two", LogLine.Source.STDOUT),
                ),
                containers = listOf("frontend", "backend"),
            ),
            onBackClick = {},
            onStopClick = {},
            onRetryClick = {},
            onSearchQueryChange = {},
            onContainerSelected = {},
            onFollowEnabledChange = {},
            onTimestampEnabledChange = {},
            onShareClick = {},
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LogViewerScreenPreviewDark() {
    MaterialTheme {
        LogViewerScreen(
            state = LogViewerUiState(isEmpty = true),
            onBackClick = {},
            onStopClick = {},
            onRetryClick = {},
            onSearchQueryChange = {},
            onContainerSelected = {},
            onFollowEnabledChange = {},
            onTimestampEnabledChange = {},
            onShareClick = {},
        )
    }
}
