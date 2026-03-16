package com.kubedroid.feature.crd.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import com.kubedroid.feature.pods.resources.ResourceListItem
import com.kubedroid.feature.pods.resources.ResourceListUiState
import com.kubedroid.feature.pods.resources.ResourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrdResourceListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_CrdResourceListScreen_loadingState_rendersProgress() {
        setScreen(
            CrdResourceListScreenState(
                selectedCrd = namespacedCrd(),
                listState = ResourceListUiState.Loading,
            ),
        )

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun test_CrdResourceListScreen_emptyState_rendersEmptyText() {
        setScreen(
            CrdResourceListScreenState(
                selectedCrd = namespacedCrd(),
                listState = ResourceListUiState.Empty,
            ),
        )

        composeRule.onNodeWithText("No custom resources found").assertIsDisplayed()
    }

    @Test
    fun test_CrdResourceListScreen_errorState_rendersErrorMessage() {
        setScreen(
            CrdResourceListScreenState(
                selectedCrd = namespacedCrd(),
                listState = ResourceListUiState.Error(cause = IllegalStateException("Forbidden")),
            ),
        )

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
    }

    @Test
    fun test_CrdResourceListScreen_successState_rendersEntries() {
        val widget = sampleItem(id = "widget/default/a", name = "widget-a")
        val gadget = sampleItem(id = "widget/default/b", name = "widget-b")
        setScreen(
            CrdResourceListScreenState(
                selectedCrd = namespacedCrd(),
                listState = ResourceListUiState.Success(items = listOf(widget, gadget)),
            ),
        )

        composeRule.onNodeWithText("widget-a").assertIsDisplayed()
        composeRule.onNodeWithText("widget-b").assertIsDisplayed()
    }

    @Test
    fun test_CrdResourceListScreen_namespacedCrd_showsNamespaceInput() {
        setScreen(
            CrdResourceListScreenState(
                selectedCrd = namespacedCrd(),
                selectedNamespace = "default",
                listState = ResourceListUiState.Empty,
            ),
        )

        composeRule.onNodeWithText("default").assertIsDisplayed()
    }

    @Test
    fun test_CrdResourceListScreen_clusterScopedCrd_hidesNamespaceInput() {
        setScreen(
            CrdResourceListScreenState(
                selectedCrd = clusterCrd(),
                listState = ResourceListUiState.Empty,
            ),
        )

        composeRule.onAllNodesWithText("Namespace").assertCountEquals(0)
    }

    @Test
    fun test_CrdResourceListScreen_refreshClick_invokesRetryCallback() {
        var refreshClicked = false
        setScreen(
            state = CrdResourceListScreenState(
                selectedCrd = namespacedCrd(),
                listState = ResourceListUiState.Empty,
            ),
            onRetryClick = { refreshClicked = true },
        )

        composeRule.onNodeWithContentDescription("Refresh custom resources").performClick()

        composeRule.runOnIdle {
            assertTrue(refreshClicked)
        }
    }

    @Test
    fun test_CrdResourceListScreen_namespacePicker_selectNamespace_invokesCallback() {
        var namespaceValue: String = "default"
        setScreen(
            state = CrdResourceListScreenState(
                selectedCrd = namespacedCrd(),
                selectedNamespace = "default",
                namespaces = listOf("default", "prod"),
                isNamespacePickerVisible = true,
                listState = ResourceListUiState.Empty,
            ),
            onNamespaceSelected = { namespaceValue = it },
        )

        composeRule.onNodeWithText("prod").performClick()

        composeRule.runOnIdle {
            assertEquals("prod", namespaceValue)
        }
    }

    @Test
    fun test_CrdResourceListScreen_rowClick_invokesSelectedItemCallback() {
        val item = sampleItem(id = "widget/default/a", name = "widget-a")
        var clicked: ResourceListItem? = null
        setScreen(
            state = CrdResourceListScreenState(
                selectedCrd = namespacedCrd(),
                listState = ResourceListUiState.Success(items = listOf(item)),
            ),
            onResourceClick = { clicked = it },
        )

        composeRule.onNodeWithText("widget-a").performClick()

        composeRule.runOnIdle {
            assertEquals(item, clicked)
        }
    }

    private fun setScreen(
        state: CrdResourceListScreenState,
        onRetryClick: () -> Unit = {},
        onNamespaceClick: () -> Unit = {},
        onNamespaceDismiss: () -> Unit = {},
        onNamespaceSelected: (String) -> Unit = {},
        onResourceClick: (ResourceListItem) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                CrdResourceListScreen(
                    state = state,
                    onRetryClick = onRetryClick,
                    onNamespaceClick = onNamespaceClick,
                    onNamespaceDismiss = onNamespaceDismiss,
                    onNamespaceSelected = onNamespaceSelected,
                    onResourceClick = onResourceClick,
                )
            }
        }
    }

    private fun namespacedCrd(): CustomResourceDefinition {
        return CustomResourceDefinition(
            name = "widgets.example.com",
            group = "example.com",
            version = "v1",
            scope = "Namespaced",
            kind = "Widget",
        )
    }

    private fun clusterCrd(): CustomResourceDefinition {
        return CustomResourceDefinition(
            name = "clusterwidgets.example.com",
            group = "example.com",
            version = "v1",
            scope = "Cluster",
            kind = "ClusterWidget",
        )
    }

    private fun sampleItem(
        id: String,
        name: String,
    ): ResourceListItem {
        return ResourceListItem(
            id = id,
            name = name,
            namespace = "default",
            kind = "Widget",
            status = ResourceStatus.HEALTHY,
        )
    }
}
