package com.kubedroid.feature.nodes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NodeListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_NodeListScreen_loadingState_rendersProgress() {
        setScreen(
            NodeListUiState(listState = NodeListContentState.Loading),
        )

        composeRule.onNodeWithContentDescription("Loading list data").assertIsDisplayed()
    }

    @Test
    fun test_NodeListScreen_emptyState_rendersEmptyText() {
        setScreen(
            NodeListUiState(listState = NodeListContentState.Empty),
        )

        composeRule.onNodeWithText("No nodes found").assertIsDisplayed()
    }

    @Test
    fun test_NodeListScreen_errorState_withCause_rendersCauseMessage() {
        setScreen(
            NodeListUiState(
                listState = NodeListContentState.Error(
                    cause = IllegalStateException("Forbidden"),
                ),
            ),
        )

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
    }

    @Test
    fun test_NodeListScreen_errorState_withoutCause_rendersDefaultMessage() {
        setScreen(
            NodeListUiState(
                listState = NodeListContentState.Error(cause = null),
            ),
        )

        composeRule.onNodeWithText("Unable to load nodes").assertIsDisplayed()
    }

    @Test
    fun test_NodeListScreen_successState_rendersRowsAndMetrics() {
        setScreen(
            NodeListUiState(
                listState = NodeListContentState.Success(
                    items = listOf(
                        NodeListItem(
                            stableKey = "node-a",
                            name = "node-a",
                            roles = listOf("control-plane"),
                            ready = true,
                            unschedulable = false,
                            cpuPercent = 12.5f,
                            memoryPercent = null,
                        ),
                        NodeListItem(
                            stableKey = "node-b",
                            name = "node-b",
                            roles = emptyList(),
                            ready = false,
                            unschedulable = true,
                            cpuPercent = 88.9f,
                            memoryPercent = 45.1f,
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("node-a").assertIsDisplayed()
        composeRule.onNodeWithText("Roles: control-plane").assertIsDisplayed()
        composeRule.onNodeWithText("12.5%").assertIsDisplayed()
        composeRule.onNodeWithText("N/A").assertIsDisplayed()
        composeRule.onNodeWithText("node-b").assertIsDisplayed()
        composeRule.onNodeWithText("Roles: worker").assertIsDisplayed()
        composeRule.onNodeWithText("Unschedulable").assertIsDisplayed()
    }

    @Test
    fun test_NodeListScreen_refreshClick_invokesRetryCallback() {
        var retryClicked = false
        setScreen(
            state = NodeListUiState(listState = NodeListContentState.Empty),
            onRetryClick = { retryClicked = true },
        )

        composeRule.onNodeWithContentDescription("Refresh nodes").performClick()

        composeRule.runOnIdle {
            assertTrue(retryClicked)
        }
    }

    @Test
    fun test_NodeListScreen_rowClick_invokesNodeCallback() {
        var clickedNodeName: String? = null
        setScreen(
            state = NodeListUiState(
                listState = NodeListContentState.Success(
                    items = listOf(
                        NodeListItem(
                            stableKey = "node-a",
                            name = "node-a",
                            roles = emptyList(),
                            ready = true,
                            unschedulable = false,
                            cpuPercent = null,
                            memoryPercent = null,
                        ),
                    ),
                ),
            ),
            onNodeClick = { clickedNodeName = it },
        )

        composeRule.onNodeWithText("node-a").performClick()

        composeRule.runOnIdle {
            assertEquals("node-a", clickedNodeName)
        }
    }

    private fun setScreen(
        state: NodeListUiState,
        onRetryClick: () -> Unit = {},
        onNodeClick: (nodeName: String) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                NodeListScreen(
                    state = state,
                    onRetryClick = onRetryClick,
                    onNodeClick = onNodeClick,
                )
            }
        }
    }
}
