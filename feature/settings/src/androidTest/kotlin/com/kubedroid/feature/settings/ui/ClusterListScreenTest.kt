package com.kubedroid.feature.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kubedroid.core.network.connection.ClusterConnectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ClusterListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_emptyState_showsEmptyMessage() {
        setScreen(
            clusters = emptyList(),
            connectionState = ClusterConnectionState.Disconnected,
        )

        composeRule.onNodeWithText("No clusters configured").assertIsDisplayed()
    }

    @Test
    fun test_populatedState_showsClustersAndActiveBadge() {
        setScreen(
            clusters = listOf(
                ClusterListItemUiModel("dev", "https://dev.example.com"),
                ClusterListItemUiModel("prod", "https://prod.example.com"),
            ),
            connectionState = ClusterConnectionState.Connected("dev"),
            activeContextName = "dev",
        )

        composeRule.onNodeWithText("dev").assertIsDisplayed()
        composeRule.onNodeWithText("prod").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Active").assertIsDisplayed()
    }

    @Test
    fun test_connectingState_showsMessageAndProgress() {
        setScreen(
            clusters = listOf(ClusterListItemUiModel("dev", "https://dev.example.com")),
            connectionState = ClusterConnectionState.Connecting("dev"),
        )

        composeRule.onNodeWithText("Connecting to dev").assertIsDisplayed()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun test_failedState_showsErrorMessageAndReason() {
        setScreen(
            clusters = listOf(ClusterListItemUiModel("dev", "https://dev.example.com")),
            connectionState = ClusterConnectionState.Failed("dev", "token expired"),
        )

        composeRule.onNodeWithText("Failed to connect to dev").assertIsDisplayed()
        composeRule.onNodeWithText("token expired").assertIsDisplayed()
    }

    @Test
    fun test_populatedState_clickCluster_invokesCallback() {
        var selected: String? = null
        composeRule.setContent {
            MaterialTheme {
                ClusterListScreen(
                    clusters = listOf(
                        ClusterListItemUiModel("dev", "https://dev.example.com"),
                        ClusterListItemUiModel("prod", "https://prod.example.com"),
                    ),
                    connectionState = ClusterConnectionState.Disconnected,
                    onClusterSelected = { selected = it },
                    onDeleteCluster = {},
                    onAddClusterClick = {},
                    onDisconnectClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Select cluster prod").performClick()
        composeRule.runOnIdle { assertEquals("prod", selected) }
    }

    private fun setScreen(
        clusters: List<ClusterListItemUiModel>,
        connectionState: ClusterConnectionState,
        activeContextName: String? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                ClusterListScreen(
                    clusters = clusters,
                    connectionState = connectionState,
                    onClusterSelected = {},
                    onDeleteCluster = {},
                    onAddClusterClick = {},
                    onDisconnectClick = {},
                    activeContextName = activeContextName,
                )
            }
        }
    }
}
