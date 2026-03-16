package com.kubedroid.feature.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.kubedroid.feature.settings.ClusterHealth
import com.kubedroid.feature.settings.ClusterSummary
import org.junit.Rule
import org.junit.Test

class DashboardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_loading_showsProgressIndicator() {
        setScreen(state = DashboardUiState.Loading)

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun test_multipleClusters_showsAllClusterCards() {
        setScreen(
            state = DashboardUiState.Success(
                summaries = listOf(
                    summary(name = "dev", health = ClusterHealth.Healthy),
                    summary(name = "prod", health = ClusterHealth.Healthy),
                    summary(name = "stage", health = ClusterHealth.Healthy),
                ),
            ),
        )

        composeRule.onNodeWithText("dev").assertIsDisplayed()
        composeRule.onNodeWithText("prod").assertIsDisplayed()
        composeRule.onNodeWithText("stage").assertIsDisplayed()
    }

    @Test
    fun test_oneDegraded_showsDegradedHealthAndWarningBadge() {
        setScreen(
            state = DashboardUiState.Success(
                summaries = listOf(
                    summary(name = "dev", health = ClusterHealth.Healthy),
                    summary(
                        name = "prod",
                        health = ClusterHealth.Degraded,
                        warnings = 2,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Degraded").assertIsDisplayed()
        composeRule.onNodeWithText("2 warnings").assertIsDisplayed()
    }

    @Test
    fun test_allHealthy_showsOnlyHealthyLabels() {
        setScreen(
            state = DashboardUiState.Success(
                summaries = listOf(
                    summary(name = "dev", health = ClusterHealth.Healthy),
                    summary(name = "prod", health = ClusterHealth.Healthy),
                ),
            ),
        )

        composeRule.onAllNodesWithText("Healthy").assertCountEquals(2)
        composeRule.onAllNodesWithText("Degraded").assertCountEquals(0)
        composeRule.onAllNodesWithText("Unreachable").assertCountEquals(0)
        composeRule.onAllNodesWithText("warnings", substring = true).assertCountEquals(0)
    }

    @Test
    fun test_empty_showsNoClustersMessage() {
        setScreen(state = DashboardUiState.Empty)

        composeRule.onNodeWithText("No clusters available").assertIsDisplayed()
    }

    private fun setScreen(state: DashboardUiState) {
        composeRule.setContent {
            MaterialTheme {
                DashboardScreen(
                    state = state,
                    isRefreshing = false,
                    onRefresh = {},
                    onClusterClick = {},
                    onAddClusterClick = {},
                )
            }
        }
    }

    private fun summary(
        name: String,
        health: ClusterHealth,
        warnings: Int = 0,
    ): ClusterSummary = ClusterSummary(
        contextName = name,
        podCount = 4,
        nodeCount = 2,
        warningEventCount = warnings,
        cpuUsagePct = 40f,
        memoryUsagePct = 50f,
        health = health,
    )
}
