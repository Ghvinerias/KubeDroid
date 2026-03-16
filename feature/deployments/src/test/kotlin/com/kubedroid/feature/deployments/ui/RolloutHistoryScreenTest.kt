package com.kubedroid.feature.deployments.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kubedroid.core.network.deployments.RolloutRevision
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RolloutHistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_RolloutHistoryScreen_loading_rendersProgress() {
        setScreen(state = RolloutHistoryUiState.Loading)

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun test_RolloutHistoryScreen_empty_rendersEmptyText() {
        setScreen(state = RolloutHistoryUiState.Empty)

        composeRule.onNodeWithText("No rollout history found").assertIsDisplayed()
    }

    @Test
    fun test_RolloutHistoryScreen_error_withoutCause_rendersDefaultAndRetryInvokesCallback() {
        var retryClicked = false
        setScreen(
            state = RolloutHistoryUiState.Error(cause = null),
            onRetryClick = { retryClicked = true },
        )

        composeRule.onNodeWithText("Unable to load rollout history").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        composeRule.runOnIdle { assertTrue(retryClicked) }
    }

    @Test
    fun test_RolloutHistoryScreen_error_withCause_rendersCauseMessage() {
        setScreen(state = RolloutHistoryUiState.Error(cause = IllegalStateException("Forbidden")))

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
    }

    @Test
    fun test_RolloutHistoryScreen_success_rendersRevisionsAndRollbackInvokesCallback() {
        var rollbackRevision: Long? = null
        val epoch = 1_704_067_200_000L
        setScreen(
            state = RolloutHistoryUiState.Success(
                revisions = listOf(
                    RolloutRevision(
                        revision = 12,
                        changeCause = "release web-12",
                        createdAtEpochMillis = epoch,
                    ),
                ),
            ),
            onRollbackClick = { rollbackRevision = it },
        )

        composeRule.onNodeWithText("Revision 12").assertIsDisplayed()
        composeRule.onNodeWithText("Cause: release web-12").assertIsDisplayed()
        composeRule.onNodeWithText("Created: ${formatUserDateTime(epoch)}").assertIsDisplayed()
        composeRule.onNodeWithText("Rollback").performClick()

        composeRule.runOnIdle {
            assertEquals(12L, rollbackRevision)
        }
    }

    @Test
    fun test_RolloutHistoryScreen_success_unknownFallbackText() {
        setScreen(
            state = RolloutHistoryUiState.Success(
                revisions = listOf(
                    RolloutRevision(
                        revision = 2,
                        changeCause = null,
                        createdAtEpochMillis = null,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Cause: Unknown").assertIsDisplayed()
        composeRule.onNodeWithText("Created: Unknown").assertIsDisplayed()
    }

    private fun setScreen(
        state: RolloutHistoryUiState,
        onRetryClick: () -> Unit = {},
        onRollbackClick: (revision: Long?) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                RolloutHistoryScreen(
                    state = state,
                    onRetryClick = onRetryClick,
                    onRollbackClick = onRollbackClick,
                )
            }
        }
    }

    private fun formatUserDateTime(epochMillis: Long): String {
        val instant = Instant.ofEpochMilli(epochMillis)
        val formatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }
}
