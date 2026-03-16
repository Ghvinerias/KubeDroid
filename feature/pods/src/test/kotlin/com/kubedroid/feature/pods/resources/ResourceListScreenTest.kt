package com.kubedroid.feature.pods.resources

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
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
class ResourceListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_ResourceListScreen_loadingState_rendersProgress() {
        setScreen(
            ResourceListScreenState(
                listState = ResourceListUiState.Loading,
            ),
        )

        composeRule.onNodeWithContentDescription("Refresh resources").assertIsDisplayed()
    }

    @Test
    fun test_ResourceListScreen_emptyState_rendersEmptyText() {
        setScreen(
            ResourceListScreenState(
                listState = ResourceListUiState.Empty,
            ),
        )

        composeRule.onNodeWithText("No resources found in", substring = true).assertIsDisplayed()
    }

    @Test
    fun test_ResourceListScreen_errorState_withoutCause_rendersDefaultErrorText() {
        setScreen(
            ResourceListScreenState(
                listState = ResourceListUiState.Error(cause = null),
            ),
        )

        composeRule.onNodeWithText("Unable to load resources").assertIsDisplayed()
    }

    @Test
    fun test_ResourceListScreen_errorState_withCause_rendersCauseMessage() {
        setScreen(
            ResourceListScreenState(
                listState = ResourceListUiState.Error(cause = IllegalStateException("Forbidden")),
            ),
        )

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
    }

    @Test
    fun test_ResourceListScreen_successState_rendersResourceRows() {
        setScreen(
            ResourceListScreenState(
                selectedNamespace = null,
                listState = ResourceListUiState.Success(
                    items = listOf(
                        sampleItem(
                            id = "pod/default/api",
                            name = "api",
                            namespace = "default",
                            kind = "Pod",
                            status = ResourceStatus.HEALTHY,
                        ),
                        sampleItem(
                            id = "deploy/prod/web",
                            name = "web",
                            namespace = "prod",
                            kind = "Deployment",
                            status = ResourceStatus.WARNING,
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("api").assertIsDisplayed()
        composeRule.onNodeWithText("default").assertIsDisplayed()
        composeRule.onNodeWithText("web").assertIsDisplayed()
        composeRule.onNodeWithText("prod").assertIsDisplayed()
        composeRule.onNodeWithText("RUNNING").assertIsDisplayed()
        composeRule.onNodeWithText("PENDING").assertIsDisplayed()
    }

    @Test
    fun test_ResourceListScreen_clickTopBarRefresh_invokesRetryCallback() {
        var retryClicked = false
        setScreen(
            state = ResourceListScreenState(listState = ResourceListUiState.Empty),
            onRetryClick = { retryClicked = true },
        )

        composeRule.onNodeWithContentDescription("Refresh resources").performClick()

        composeRule.runOnIdle { assertTrue(retryClicked) }
    }

    @Test
    fun test_ResourceListScreen_clickErrorRetryButton_invokesRetryCallback() {
        var retryClicked = false
        setScreen(
            state = ResourceListScreenState(
                listState = ResourceListUiState.Error(cause = IllegalStateException("Any error")),
            ),
            onRetryClick = { retryClicked = true },
        )

        composeRule.onNodeWithText("Retry").performClick()

        composeRule.runOnIdle { assertTrue(retryClicked) }
    }

    @Test
    fun test_ResourceListScreen_namespaceButton_showsAllNamespacesAndInvokesClick() {
        var namespaceClicked = false
        setScreen(
            state = ResourceListScreenState(
                selectedNamespace = null,
                listState = ResourceListUiState.Empty,
            ),
            onNamespaceClick = { namespaceClicked = true },
        )

        composeRule.onNodeWithText("All namespaces").assertIsDisplayed()
        composeRule.onNodeWithText("All namespaces").performClick()

        composeRule.runOnIdle { assertTrue(namespaceClicked) }
    }

    @Test
    fun test_ResourceListScreen_namespaceButton_showsSelectedNamespace() {
        setScreen(
            ResourceListScreenState(
                selectedNamespace = "prod",
                listState = ResourceListUiState.Empty,
            ),
        )

        composeRule.onNodeWithText("prod").assertIsDisplayed()
    }

    @Test
    fun test_ResourceListScreen_namespaceBottomSheet_selectNamespace_invokesCallback() {
        var selectedNamespace: String? = "unset"
        setScreen(
            state = ResourceListScreenState(
                selectedNamespace = "default",
                namespaces = listOf("default", "prod"),
                isNamespaceSwitcherVisible = true,
                listState = ResourceListUiState.Empty,
            ),
            onNamespaceSelected = { selectedNamespace = it },
        )

        composeRule.onNodeWithText("Switch namespace").assertIsDisplayed()
        composeRule.onNodeWithText("prod").performClick()

        composeRule.runOnIdle { assertEquals("prod", selectedNamespace) }
    }

    @Test
    fun test_ResourceListScreen_namespaceBottomSheet_selectAllNamespaces_invokesNullCallback() {
        var selectedNamespace: String? = "default"
        setScreen(
            state = ResourceListScreenState(
                selectedNamespace = "default",
                namespaces = listOf("default", "prod"),
                isNamespaceSwitcherVisible = true,
                listState = ResourceListUiState.Empty,
            ),
            onNamespaceSelected = { selectedNamespace = it },
        )

        composeRule.onNodeWithText("All namespaces").performClick()

        composeRule.runOnIdle { assertEquals(null, selectedNamespace) }
    }

    @Test
    fun test_StatusBadge_healthy_rendersLabel() {
        setStatusBadge(ResourceStatus.HEALTHY)

        composeRule.onNodeWithText("RUNNING").assertIsDisplayed()
    }

    @Test
    fun test_StatusBadge_warning_rendersLabel() {
        setStatusBadge(ResourceStatus.WARNING)

        composeRule.onNodeWithText("PENDING").assertIsDisplayed()
    }

    @Test
    fun test_StatusBadge_error_rendersLabel() {
        setStatusBadge(ResourceStatus.ERROR)

        composeRule.onNodeWithText("FAILED").assertIsDisplayed()
    }

    @Test
    fun test_StatusBadge_unknown_rendersLabel() {
        setStatusBadge(ResourceStatus.UNKNOWN)

        composeRule.onNodeWithText("UNKNOWN").assertIsDisplayed()
    }

    private fun setStatusBadge(status: ResourceStatus) {
        composeRule.setContent {
            MaterialTheme {
                StatusBadge(status = status)
            }
        }
    }

    private fun setScreen(
        state: ResourceListScreenState,
        onNamespaceClick: () -> Unit = {},
        onNamespaceDismiss: () -> Unit = {},
        onNamespaceSelected: (String?) -> Unit = {},
        onRetryClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ResourceListScreen(
                    state = state,
                    onNamespaceClick = onNamespaceClick,
                    onNamespaceDismiss = onNamespaceDismiss,
                    onNamespaceSelected = onNamespaceSelected,
                    onRetryClick = onRetryClick,
                )
            }
        }
    }

    private fun sampleItem(
        id: String,
        name: String,
        namespace: String,
        kind: String,
        status: ResourceStatus,
    ): ResourceListItem = ResourceListItem(
        id = id,
        name = name,
        namespace = namespace,
        kind = kind,
        status = status,
    )
}
