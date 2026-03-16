package com.kubedroid.feature.pods.resources

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResourceListAdaptiveLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactWindowSizeClass_usesSinglePaneAndDoesNotRenderDetailPane() {
        var clickCount = 0

        composeRule.setContent {
            MaterialTheme {
                ResourceListScreen(
                    state = sampleState(),
                    onNamespaceClick = {},
                    onNamespaceDismiss = {},
                    onNamespaceSelected = {},
                    onRetryClick = {},
                    onResourceClick = { clickCount += 1 },
                    useTwoPane = false,
                    detailPane = {
                        androidx.compose.material3.Text(
                            text = "detail",
                            modifier = Modifier.testTag("detail-pane"),
                        )
                    },
                )
            }
        }

        composeRule.onAllNodesWithTag("detail-pane").assertCountEquals(0)
        composeRule.onNodeWithText("pod-a").performClick()
        composeRule.runOnIdle { assertEquals(1, clickCount) }
    }

    @Test
    fun expandedWindowSizeClass_restoresSelectedItemAfterStateRestore() {
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            var selectedResourceId by rememberSaveable { mutableStateOf<String?>(null) }

            MaterialTheme {
                Column {
                    androidx.compose.material3.Text(
                        text = selectedResourceId.orEmpty(),
                        modifier = Modifier.testTag("selected-resource-id"),
                    )
                    ResourceListScreen(
                        state = sampleState(),
                        onNamespaceClick = {},
                        onNamespaceDismiss = {},
                        onNamespaceSelected = {},
                        onRetryClick = {},
                        onResourceClick = { item -> selectedResourceId = item.id },
                        useTwoPane = true,
                        detailPane = {
                            androidx.compose.material3.Text(
                                text = selectedResourceId.orEmpty(),
                                modifier = Modifier.testTag("detail-selected-resource-id"),
                            )
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithText("pod-a").performClick()
        composeRule.onNodeWithTag("selected-resource-id").assertTextEquals("default/pod-a")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("selected-resource-id").assertTextEquals("default/pod-a")
        composeRule.onNodeWithTag("detail-selected-resource-id").assertIsDisplayed()
    }

    private fun sampleState(): ResourceListScreenState = ResourceListScreenState(
        selectedNamespace = null,
        namespaces = listOf("default"),
        listState = ResourceListUiState.Success(
            items = listOf(
                ResourceListItem(
                    id = "default/pod-a",
                    name = "pod-a",
                    namespace = "default",
                    kind = "Pod",
                    status = ResourceStatus.HEALTHY,
                ),
            ),
        ),
        isNamespaceSwitcherVisible = false,
    )
}
