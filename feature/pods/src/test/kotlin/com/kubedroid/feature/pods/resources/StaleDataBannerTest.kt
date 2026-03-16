package com.kubedroid.feature.pods.resources

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StaleDataBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resourceListScreen_whenDataIsFromCache_showsStaleDataBanner() {
        setResourceListScreen(
            ResourceListScreenState(
                listState = ResourceListUiState.Empty,
                isDataFromCache = true,
                lastUpdatedAtEpochMillis = 1_000L,
            ),
        )

        composeRule.onNodeWithText("Showing cached data").assertIsDisplayed()
    }

    @Test
    fun resourceListScreen_whenDataIsFresh_hidesStaleDataBanner() {
        setResourceListScreen(
            ResourceListScreenState(
                listState = ResourceListUiState.Empty,
                isDataFromCache = false,
                lastUpdatedAtEpochMillis = 1_000L,
            ),
        )

        composeRule.onAllNodesWithText("Showing cached data").assertCountEquals(0)
    }

    @Test
    fun staleDataBanner_unrelatedParentRecomposition_keepsBannerCompositionStable() {
        var parentTick by mutableIntStateOf(0)
        var bannerCompositions by mutableIntStateOf(0)
        val fixedNow = 5_000L
        val fixedLastFetched = 1_000L

        composeRule.setContent {
            MaterialTheme {
                Column {
                    Text(text = "parent-$parentTick")
                    CountingStaleDataBanner(
                        lastFetchedAtEpochMillis = fixedLastFetched,
                        nowEpochMillis = fixedNow,
                        onComposed = {
                            bannerCompositions += 1
                        },
                    )
                }
            }
        }

        composeRule.runOnIdle {
            assertTrue(bannerCompositions > 0)
        }
        val initialCompositions = bannerCompositions

        composeRule.runOnIdle { parentTick += 1 }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(initialCompositions, bannerCompositions)
        }
    }

    @Test
    fun staleDataBanner_whenObservedInputChanges_recomposes() {
        var lastFetchedAt by mutableLongStateOf(1_000L)
        var bannerCompositions by mutableIntStateOf(0)
        val fixedNow = 5_000L

        composeRule.setContent {
            MaterialTheme {
                val stableCallback = remember {
                    {
                        bannerCompositions += 1
                    }
                }
                CountingStaleDataBanner(
                    lastFetchedAtEpochMillis = lastFetchedAt,
                    nowEpochMillis = fixedNow,
                    onComposed = stableCallback,
                )
            }
        }

        composeRule.runOnIdle {
            assertTrue(bannerCompositions > 0)
        }
        val initialCompositions = bannerCompositions

        composeRule.runOnIdle { lastFetchedAt = 2_000L }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue(bannerCompositions > initialCompositions)
        }
    }

    @Composable
    private fun CountingStaleDataBanner(
        lastFetchedAtEpochMillis: Long?,
        nowEpochMillis: Long,
        onComposed: () -> Unit,
    ) {
        SideEffect(onComposed)
        StaleDataBanner(
            lastFetchedAtEpochMillis = lastFetchedAtEpochMillis,
            nowEpochMillis = nowEpochMillis,
        )
    }

    private fun setResourceListScreen(state: ResourceListScreenState) {
        composeRule.setContent {
            MaterialTheme {
                ResourceListScreen(
                    state = state,
                    onNamespaceClick = {},
                    onNamespaceDismiss = {},
                    onNamespaceSelected = {},
                    onRetryClick = {},
                )
            }
        }
    }
}
