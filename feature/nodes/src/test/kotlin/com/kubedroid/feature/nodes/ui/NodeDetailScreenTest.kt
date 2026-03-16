package com.kubedroid.feature.nodes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kubedroid.core.network.nodes.Node
import com.kubedroid.core.network.nodes.NodeCondition
import com.kubedroid.core.network.nodes.NodeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NodeDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_NodeDetailScreen_loadingState_rendersProgress() {
        setScreen(
            NodeDetailUiState(
                nodeName = "node-a",
                isLoading = true,
                node = null,
            ),
        )

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun test_NodeDetailScreen_errorState_rendersCauseMessage() {
        setScreen(
            NodeDetailUiState(
                nodeName = "node-a",
                isLoading = false,
                error = IllegalStateException("Forbidden"),
            ),
        )

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
    }

    @Test
    fun test_NodeDetailScreen_notFoundState_rendersFallbackMessage() {
        setScreen(
            NodeDetailUiState(
                nodeName = "node-a",
                isLoading = false,
                node = null,
                error = null,
            ),
        )

        composeRule.onNodeWithText("Node not found").assertIsDisplayed()
    }

    @Test
    fun test_NodeDetailScreen_infoTab_rendersDetailsAndActions() {
        setScreen(
            NodeDetailUiState(
                nodeName = "node-a",
                isLoading = false,
                selectedTab = NodeDetailTab.INFO,
                node = sampleNode(unschedulable = false),
            ),
        )

        composeRule.onNodeWithText("Node node-a").assertIsDisplayed()
        composeRule.onNodeWithText("Name").assertIsDisplayed()
        composeRule.onNodeWithText("node-a").assertIsDisplayed()
        composeRule.onNodeWithText("Cordon").assertIsDisplayed()
        composeRule.onNodeWithText("Drain").assertIsDisplayed()
    }

    @Test
    fun test_NodeDetailScreen_backClick_invokesCallback() {
        var backClicked = false
        setScreen(
            state = NodeDetailUiState(
                nodeName = "node-a",
                isLoading = false,
                node = sampleNode(),
            ),
            onBackClick = { backClicked = true },
        )

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.runOnIdle {
            assertTrue(backClicked)
        }
    }

    @Test
    fun test_NodeDetailScreen_tabClick_invokesSelectionCallback() {
        var selectedTab: NodeDetailTab? = null
        setScreen(
            state = NodeDetailUiState(
                nodeName = "node-a",
                isLoading = false,
                selectedTab = NodeDetailTab.INFO,
                node = sampleNode(),
            ),
            onTabSelected = { selectedTab = it },
        )

        composeRule.onNodeWithText("Conditions").performClick()

        composeRule.runOnIdle {
            assertEquals(NodeDetailTab.CONDITIONS, selectedTab)
        }
    }

    @Test
    fun test_NodeDetailScreen_drainActionClick_invokesConfirmationRequest() {
        var requestedAction: NodeActionType? = null
        setScreen(
            state = NodeDetailUiState(
                nodeName = "node-a",
                isLoading = false,
                node = sampleNode(),
            ),
            onRequestActionConfirmation = { requestedAction = it },
        )

        composeRule.onNodeWithText("Drain").performClick()

        composeRule.runOnIdle {
            assertEquals(NodeActionType.DRAIN, requestedAction)
        }
    }

    @Test
    fun test_NodeDetailScreen_activeConfirmation_rendersDialogAndWiresCallbacks() {
        var confirmClicked = false
        var dismissClicked = false
        setScreen(
            state = NodeDetailUiState(
                nodeName = "node-a",
                isLoading = false,
                node = sampleNode(),
                activeConfirmation = NodeActionType.DRAIN,
            ),
            onConfirmAction = { confirmClicked = true },
            onDismissActionConfirmation = { dismissClicked = true },
        )

        composeRule.onNodeWithText("Drain node?").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.runOnIdle {
            assertTrue(confirmClicked)
            assertTrue(dismissClicked)
        }
    }

    @Test
    fun test_NodeDetailScreen_podsTab_rendersDrainEventRows() {
        setScreen(
            NodeDetailUiState(
                nodeName = "node-a",
                isLoading = false,
                selectedTab = NodeDetailTab.PODS,
                node = sampleNode(),
                drainState = DrainProgressUiState(
                    isVisible = false,
                    events = listOf(
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
                            retryDelayMillis = 1000L,
                        ),
                        DrainProgressEventUi(
                            messageType = DrainEventMessageType.POD_EVICTED,
                            namespace = "default",
                            podName = "api-1",
                            attempt = null,
                            retryDelayMillis = null,
                        ),
                        DrainProgressEventUi(
                            messageType = DrainEventMessageType.COMPLETED_SUCCESS,
                            namespace = null,
                            podName = null,
                            attempt = null,
                            retryDelayMillis = null,
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("default/api-1 - Eviction Attempt").assertIsDisplayed()
        composeRule.onNodeWithText("default/api-1 - Waiting on PDB").assertIsDisplayed()
        composeRule.onNodeWithText("default/api-1 - Evicted").assertIsDisplayed()
    }

    private fun setScreen(
        state: NodeDetailUiState,
        onBackClick: () -> Unit = {},
        onTabSelected: (NodeDetailTab) -> Unit = {},
        onRequestActionConfirmation: (NodeActionType) -> Unit = {},
        onDismissActionConfirmation: () -> Unit = {},
        onConfirmAction: () -> Unit = {},
        onConsumeActionMessage: () -> Unit = {},
        onDismissDrainDialog: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                NodeDetailScreen(
                    state = state,
                    onBackClick = onBackClick,
                    onTabSelected = onTabSelected,
                    onRequestActionConfirmation = onRequestActionConfirmation,
                    onDismissActionConfirmation = onDismissActionConfirmation,
                    onConfirmAction = onConfirmAction,
                    onConsumeActionMessage = onConsumeActionMessage,
                    onDismissDrainDialog = onDismissDrainDialog,
                )
            }
        }
    }

    private fun sampleNode(unschedulable: Boolean = true): Node = Node(
        name = "node-a",
        roles = listOf("worker"),
        kubeletVersion = "v1.30.0",
        internalIp = "10.0.0.10",
        externalIp = "34.10.10.10",
        ready = true,
        unschedulable = unschedulable,
        conditions = listOf(
            NodeCondition(
                type = "Ready",
                status = "True",
                reason = null,
                message = null,
                lastTransitionTimeEpochMillis = null,
            ),
        ),
        metrics = NodeMetrics(
            cpuUsageMillicores = 1000L,
            memoryUsageBytes = 1024L * 1024L * 1024L,
            cpuUsagePercent = 50f,
            memoryUsagePercent = 25f,
        ),
    )
}
