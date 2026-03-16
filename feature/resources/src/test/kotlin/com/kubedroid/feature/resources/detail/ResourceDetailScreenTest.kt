package com.kubedroid.feature.resources.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kubedroid.core.network.resources.KubeEvent
import com.kubedroid.core.network.resources.ResourceDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResourceDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_ResourceDetailScreen_loadingState_rendersProgress() {
        setScreen(state = ResourceDetailUiState.Loading)

        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun test_ResourceDetailScreen_notFoundState_rendersMessage() {
        setScreen(state = ResourceDetailUiState.NotFound)

        composeRule.onNodeWithText("Resource not found").assertIsDisplayed()
    }

    @Test
    fun test_ResourceDetailScreen_errorState_rendersCauseAndInvokesRetry() {
        var retryClicked = false
        setScreen(
            state = ResourceDetailUiState.Error(cause = IllegalStateException("Forbidden")),
            onRetryClick = { retryClicked = true },
        )

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        composeRule.runOnIdle { assertTrue(retryClicked) }
    }

    @Test
    fun test_ResourceDetailScreen_contentState_rendersTitleAndInvokesActions() {
        var backClicked = false
        var selectedTab: ResourceDetailTab? = null
        var editedYaml: String? = null
        val state = ResourceDetailUiState.Content(
            detail = sampleDetail(),
            yamlDraft = "apiVersion: v1\nkind: Pod\nmetadata:\n  name: demo-updated",
        )
        setScreen(
            state = state,
            selectedTab = ResourceDetailTab.YAML,
            onBackClick = { backClicked = true },
            onTabSelected = { selectedTab = it },
            onEditYamlClick = { editedYaml = it },
        )

        composeRule.onNodeWithText("Pod/demo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Edit YAML").performClick()
        composeRule.onNodeWithText("Events").performClick()

        composeRule.runOnIdle {
            assertTrue(backClicked)
            assertEquals(ResourceDetailTab.EVENTS, selectedTab)
            assertEquals(state.yamlDraft, editedYaml)
        }
    }

    private fun setScreen(
        state: ResourceDetailUiState,
        selectedTab: ResourceDetailTab = ResourceDetailTab.YAML,
        onTabSelected: (ResourceDetailTab) -> Unit = {},
        onEditYamlClick: (String) -> Unit = {},
        onBackClick: () -> Unit = {},
        onRetryClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ResourceDetailScreen(
                    state = state,
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    onEditYamlClick = onEditYamlClick,
                    onBackClick = onBackClick,
                    onRetryClick = onRetryClick,
                )
            }
        }
    }

    private fun sampleDetail(): ResourceDetail = ResourceDetail(
        name = "demo",
        namespace = "default",
        kind = "Pod",
        apiVersion = "v1",
        resourceVersion = "12",
        yaml = "apiVersion: v1\nkind: Pod\nmetadata:\n  name: demo",
        events = listOf(
            KubeEvent(
                reason = "Started",
                type = "Normal",
                message = "Container started",
                source = "kubelet",
                count = 1,
                firstTimestampEpochMillis = 1_700_000_000_000,
                lastTimestampEpochMillis = 1_700_000_010_000,
            ),
        ),
    )
}
