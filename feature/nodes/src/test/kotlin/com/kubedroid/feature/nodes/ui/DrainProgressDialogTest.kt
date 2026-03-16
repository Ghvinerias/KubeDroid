package com.kubedroid.feature.nodes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DrainProgressDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_DrainProgressDialog_notVisible_rendersNothing() {
        setDialog(
            state = DrainProgressUiState(isVisible = false),
        )

        composeRule.onAllNodesWithText("Draining node-a").assertCountEquals(0)
    }

    @Test
    fun test_DrainProgressDialog_runningState_disablesCloseButton() {
        setDialog(
            state = DrainProgressUiState(
                isVisible = true,
                nodeName = "node-a",
                totalPods = 4,
                evictedPods = 1,
                events = listOf(
                    DrainProgressEventUi(
                        messageType = DrainEventMessageType.STARTED,
                        namespace = null,
                        podName = null,
                        attempt = null,
                        retryDelayMillis = null,
                    ),
                ),
                isCompleted = false,
            ),
        )

        composeRule.onNodeWithText("Draining node-a").assertIsDisplayed()
        composeRule.onNodeWithText("Evicted 1/4 pods").assertIsDisplayed()
        composeRule.onNodeWithText("Running...").assertIsNotEnabled()
    }

    @Test
    fun test_DrainProgressDialog_completedState_enablesCloseAndInvokesDismiss() {
        var dismissed = false
        setDialog(
            state = DrainProgressUiState(
                isVisible = true,
                nodeName = "node-a",
                totalPods = 1,
                evictedPods = 1,
                events = listOf(
                    DrainProgressEventUi(
                        messageType = DrainEventMessageType.COMPLETED_SUCCESS,
                        namespace = null,
                        podName = null,
                        attempt = null,
                        retryDelayMillis = null,
                    ),
                ),
                isCompleted = true,
            ),
            onDismissRequest = { dismissed = true },
        )

        composeRule.onNodeWithText("Close").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertTrue(dismissed)
        }
    }

    @Test
    fun test_DrainProgressDialog_rendersEventMessages() {
        setDialog(
            state = DrainProgressUiState(
                isVisible = true,
                nodeName = "node-a",
                totalPods = 2,
                evictedPods = 1,
                events = listOf(
                    DrainProgressEventUi(
                        messageType = DrainEventMessageType.STARTED,
                        namespace = null,
                        podName = null,
                        attempt = null,
                        retryDelayMillis = null,
                    ),
                    DrainProgressEventUi(
                        messageType = DrainEventMessageType.EVICTION_ATTEMPT,
                        namespace = "default",
                        podName = "api-1",
                        attempt = 1,
                        retryDelayMillis = null,
                    ),
                    DrainProgressEventUi(
                        messageType = DrainEventMessageType.WAITING_ON_PDB,
                        namespace = "default",
                        podName = "api-1",
                        attempt = 2,
                        retryDelayMillis = 3000L,
                    ),
                    DrainProgressEventUi(
                        messageType = DrainEventMessageType.POD_EVICTED,
                        namespace = "default",
                        podName = "api-1",
                        attempt = null,
                        retryDelayMillis = null,
                    ),
                ),
                isCompleted = false,
            ),
        )

        composeRule.onNodeWithText("Drain started").assertIsDisplayed()
        composeRule.onNodeWithText("Evicting default/api-1 (attempt 1)").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting on PDB for default/api-1 (attempt 2, retry in 3s)").assertIsDisplayed()
        composeRule.onNodeWithText("Evicted default/api-1").assertIsDisplayed()
    }

    private fun setDialog(
        state: DrainProgressUiState,
        onDismissRequest: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                DrainProgressDialog(
                    state = state,
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }
}
