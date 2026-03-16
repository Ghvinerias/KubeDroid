package com.kubedroid.feature.pods.logs.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kubedroid.feature.pods.R
import com.kubedroid.feature.pods.logs.LogLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LogViewerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_LogViewerScreen_loadingState_rendersProgress() {
        setScreen(LogViewerUiState(isLoading = true))

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun test_LogViewerScreen_successState_showsCorrectData() {
        val line = LogLine(
            streamId = "container-1",
            timestamp = "2026-01-01T00:00:00Z",
            message = "container ready",
            source = LogLine.Source.STDOUT,
        )
        setScreen(
            LogViewerUiState(
                lines = listOf(line),
                visibleLines = listOf(line),
                containers = listOf("container-1"),
            ),
        )

        composeRule.onNodeWithText("container ready", substring = true).assertIsDisplayed()
    }

    @Test
    fun test_LogViewerScreen_errorState_showsErrorMessage() {
        setScreen(LogViewerUiState(errorResId = R.string.log_viewer_error_generic))

        composeRule.onNodeWithText("Unable to load pod logs").assertIsDisplayed()
    }

    @Test
    fun test_LogViewerScreen_emptyState_showsEmptyView() {
        setScreen(LogViewerUiState(isEmpty = true))

        composeRule.onNodeWithText("No logs available").assertIsDisplayed()
    }

    @Test
    fun test_LogViewerScreen_shareDisabledWithoutVisibleLines() {
        setScreen(LogViewerUiState(isEmpty = true))

        composeRule.onNodeWithContentDescription("Share logs").assertIsNotEnabled()
    }

    @Test
    fun test_LogViewerScreen_shareEnabledAndSendsVisibleLogText() {
        var sharedText: String? = null
        val line = LogLine(
            streamId = "container-1",
            timestamp = "2026-01-01T00:00:00Z",
            message = "line one",
            source = LogLine.Source.STDOUT,
        )
        setScreen(
            LogViewerUiState(
                lines = listOf(line),
                visibleLines = listOf(line),
                isTimestampEnabled = true,
            ),
            onShareClick = { sharedText = it },
        )

        composeRule.onNodeWithContentDescription("Share logs").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("2026-01-01T00:00:00Z line one", sharedText)
        }
    }

    @Test
    fun test_LogViewerScreen_clickBack_invokesCallback() {
        var clicked = false
        setScreen(
            state = LogViewerUiState(isEmpty = true),
            onBackClick = { clicked = true },
        )

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun test_LogViewerScreen_clickStop_invokesCallback() {
        var clicked = false
        setScreen(
            state = LogViewerUiState(isEmpty = true),
            onStopClick = { clicked = true },
        )

        composeRule.onNodeWithContentDescription("Stop log stream").performClick()

        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun test_LogViewerScreen_clickRetry_invokesCallback() {
        var clicked = false
        setScreen(
            state = LogViewerUiState(errorResId = R.string.log_viewer_error_generic),
            onRetryClick = { clicked = true },
        )

        composeRule.onNodeWithContentDescription("Retry log stream").performClick()

        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun test_LogViewerScreen_clearSearch_resetsQuery() {
        var latestQuery = "init"
        setScreen(
            state = LogViewerUiState(searchQuery = "error", isEmpty = true),
            onSearchQueryChange = { latestQuery = it },
        )

        composeRule.onNodeWithContentDescription("Clear search query").performClick()

        composeRule.runOnIdle { assertEquals("", latestQuery) }
    }

    @Test
    fun test_LogViewerScreen_clickContainerTab_invokesSelectionCallback() {
        var selectedContainer: String? = "unset"
        val lines = listOf(
            LogLine("api", null, "api line", LogLine.Source.STDOUT),
            LogLine("worker", null, "worker line", LogLine.Source.STDOUT),
        )
        setScreen(
            state = LogViewerUiState(
                lines = lines,
                visibleLines = lines,
                containers = listOf("api", "worker"),
            ),
            onContainerSelected = { selectedContainer = it },
        )

        composeRule.onNodeWithText("worker").performClick()

        composeRule.runOnIdle { assertEquals("worker", selectedContainer) }
    }

    @Test
    fun test_LogViewerScreen_toggleChips_invokesCallbacks() {
        var followEnabled: Boolean? = null
        var timestampEnabled: Boolean? = null
        setScreen(
            state = LogViewerUiState(isEmpty = true, isFollowEnabled = true, isTimestampEnabled = true),
            onFollowEnabledChange = { followEnabled = it },
            onTimestampEnabledChange = { timestampEnabled = it },
        )

        composeRule.onNodeWithText("Follow").performClick()
        composeRule.onNodeWithText("Timestamps").performClick()

        composeRule.runOnIdle {
            assertEquals(false, followEnabled)
            assertEquals(false, timestampEnabled)
        }
    }

    private fun setScreen(
        state: LogViewerUiState,
        onBackClick: () -> Unit = {},
        onStopClick: () -> Unit = {},
        onRetryClick: () -> Unit = {},
        onSearchQueryChange: (String) -> Unit = {},
        onContainerSelected: (String?) -> Unit = {},
        onFollowEnabledChange: (Boolean) -> Unit = {},
        onTimestampEnabledChange: (Boolean) -> Unit = {},
        onShareClick: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                LogViewerScreen(
                    state = state,
                    onBackClick = onBackClick,
                    onStopClick = onStopClick,
                    onRetryClick = onRetryClick,
                    onSearchQueryChange = onSearchQueryChange,
                    onContainerSelected = onContainerSelected,
                    onFollowEnabledChange = onFollowEnabledChange,
                    onTimestampEnabledChange = onTimestampEnabledChange,
                    onShareClick = onShareClick,
                )
            }
        }
    }
}
