package com.kubedroid.feature.deployments.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeploymentListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_DeploymentListScreen_loadingState_rendersProgress() {
        setScreen(
            DeploymentListUiState(listState = DeploymentListContentState.Loading),
        )

        composeRule.onNodeWithContentDescription("Loading list data").assertIsDisplayed()
    }

    @Test
    fun test_DeploymentListScreen_emptyState_rendersEmptyText() {
        setScreen(
            DeploymentListUiState(listState = DeploymentListContentState.Empty),
        )

        composeRule.onNodeWithText("No deployments found").assertIsDisplayed()
    }

    @Test
    fun test_DeploymentListScreen_errorState_withCause_rendersCauseMessage() {
        setScreen(
            DeploymentListUiState(
                listState = DeploymentListContentState.Error(
                    cause = IllegalStateException("Forbidden"),
                ),
            ),
        )

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
    }

    @Test
    fun test_DeploymentListScreen_errorState_withoutCause_rendersDefaultError() {
        setScreen(
            DeploymentListUiState(
                listState = DeploymentListContentState.Error(cause = null),
            ),
        )

        composeRule.onNodeWithText("Unable to load deployments").assertIsDisplayed()
    }

    @Test
    fun test_DeploymentListScreen_successState_rendersRows() {
        setScreen(
            DeploymentListUiState(
                listState = DeploymentListContentState.Success(
                    items = listOf(
                        DeploymentListItem(
                            stableKey = "default/api",
                            name = "api",
                            namespace = "default",
                            desiredReplicas = 3,
                            readyReplicas = 2,
                            updatedReplicas = 2,
                            availableReplicas = 2,
                            observedGeneration = 7,
                        ),
                        DeploymentListItem(
                            stableKey = "prod/worker",
                            name = "worker",
                            namespace = "prod",
                            desiredReplicas = 1,
                            readyReplicas = 1,
                            updatedReplicas = 1,
                            availableReplicas = 1,
                            observedGeneration = null,
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("api").assertIsDisplayed()
        composeRule.onNodeWithText("2/3 ready").assertIsDisplayed()
        composeRule.onNodeWithText("gen 7").assertIsDisplayed()
        composeRule.onNodeWithText("worker").assertIsDisplayed()
        composeRule.onNodeWithText("1/1 ready").assertIsDisplayed()
    }

    @Test
    fun test_DeploymentListScreen_refreshClick_invokesRetryCallback() {
        var retryClicked = false
        setScreen(
            state = DeploymentListUiState(listState = DeploymentListContentState.Empty),
            onRetryClick = { retryClicked = true },
        )

        composeRule.onNodeWithContentDescription("Refresh deployments").performClick()

        composeRule.runOnIdle {
            assertTrue(retryClicked)
        }
    }

    @Test
    fun test_DeploymentListScreen_rowClick_invokesDeploymentCallbackWithNamespaceAndName() {
        var clickedNamespace: String? = null
        var clickedName: String? = null
        setScreen(
            state = DeploymentListUiState(
                listState = DeploymentListContentState.Success(
                    items = listOf(
                        DeploymentListItem(
                            stableKey = "default/api",
                            name = "api",
                            namespace = "default",
                            desiredReplicas = 3,
                            readyReplicas = 3,
                            updatedReplicas = 3,
                            availableReplicas = 3,
                            observedGeneration = null,
                        ),
                    ),
                ),
            ),
            onDeploymentClick = { namespace, deploymentName ->
                clickedNamespace = namespace
                clickedName = deploymentName
            },
        )

        composeRule.onNodeWithText("api").performClick()

        composeRule.runOnIdle {
            assertEquals("default", clickedNamespace)
            assertEquals("api", clickedName)
        }
    }

    private fun setScreen(
        state: DeploymentListUiState,
        onRetryClick: () -> Unit = {},
        onDeploymentClick: (namespace: String, deploymentName: String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            MaterialTheme {
                DeploymentListScreen(
                    state = state,
                    selectedNamespace = state.namespace,
                    namespaces = state.availableNamespaces,
                    onNamespaceSelected = {},
                    onRetryClick = onRetryClick,
                    onDeploymentClick = onDeploymentClick,
                )
            }
        }
    }
}
