package com.kubedroid.feature.metrics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetricsGraphScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingState_rendersProgressIndicatorAndLoadingText() {
        setScreen(
            cpuSeries = emptyList(),
            memorySeries = emptyList(),
            isLoading = true,
            isMetricsServerAvailable = true,
        )

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        composeRule.onNodeWithText("Loading metrics").assertIsDisplayed()
    }

    @Test
    fun dataPresentState_rendersChartsSectionTitles() {
        setScreen(
            cpuSeries = listOf(
                MetricSeriesPoint(timestampMillis = 1L, value = 100f),
                MetricSeriesPoint(timestampMillis = 2L, value = 150f),
            ),
            memorySeries = listOf(
                MetricSeriesPoint(timestampMillis = 1L, value = 256f),
                MetricSeriesPoint(timestampMillis = 2L, value = 512f),
            ),
            isLoading = false,
            isMetricsServerAvailable = true,
        )

        composeRule.onNodeWithText("CPU Usage").assertIsDisplayed()
        composeRule.onNodeWithText("Memory Usage").assertIsDisplayed()
        composeRule.onAllNodesWithText("No metrics available").assertCountEquals(0)
        composeRule.onAllNodesWithText("Metrics Server is unavailable").assertCountEquals(0)
    }

    @Test
    fun serverUnavailableState_rendersUnavailableMessage() {
        setScreen(
            cpuSeries = emptyList(),
            memorySeries = emptyList(),
            isLoading = false,
            isMetricsServerAvailable = false,
        )

        composeRule.onNodeWithText("Metrics Server is unavailable").assertIsDisplayed()
        composeRule.onAllNodesWithText("CPU Usage").assertCountEquals(0)
        composeRule.onAllNodesWithText("Memory Usage").assertCountEquals(0)
    }

    private fun setScreen(
        cpuSeries: List<MetricSeriesPoint>,
        memorySeries: List<MetricSeriesPoint>,
        isLoading: Boolean,
        isMetricsServerAvailable: Boolean,
    ) {
        composeRule.setContent {
            MaterialTheme {
                MetricsGraphScreen(
                    cpuSeries = cpuSeries,
                    memorySeries = memorySeries,
                    isLoading = isLoading,
                    isMetricsServerAvailable = isMetricsServerAvailable,
                )
            }
        }
    }
}
