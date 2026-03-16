package com.kubedroid.feature.crd.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import com.kubedroid.feature.pods.resources.ResourceListUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrdListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_CrdListScreen_loadingState_rendersProgress() {
        setScreen(
            CrdListScreenState(
                listState = ResourceListUiState.Loading,
            ),
        )

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun test_CrdListScreen_emptyState_rendersEmptyText() {
        setScreen(
            CrdListScreenState(
                listState = ResourceListUiState.Empty,
            ),
        )

        composeRule.onNodeWithText("No CRDs found").assertIsDisplayed()
    }

    @Test
    fun test_CrdListScreen_errorState_rendersErrorMessage() {
        setScreen(
            CrdListScreenState(
                listState = ResourceListUiState.Error(cause = IllegalStateException("Forbidden")),
            ),
        )

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
    }

    @Test
    fun test_CrdListScreen_successState_rendersEntries() {
        val alpha = sampleCrd(name = "alphas.example.com", kind = "Alpha")
        val zed = sampleCrd(name = "zeds.example.com", kind = "Zed")
        setScreen(
            CrdListScreenState(
                listState = ResourceListUiState.Success(
                    items = listOf(
                        CrdListItem(definition = alpha, isFavourite = false),
                        CrdListItem(definition = zed, isFavourite = false),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("alphas.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("zeds.example.com").assertIsDisplayed()
    }

    @Test
    fun test_CrdListScreen_favouritesExist_showsFavouriteAndAllHeaders() {
        setScreen(
            CrdListScreenState(
                listState = ResourceListUiState.Success(
                    items = listOf(
                        CrdListItem(definition = sampleCrd(name = "alphas.example.com", kind = "Alpha"), isFavourite = true),
                        CrdListItem(definition = sampleCrd(name = "zeds.example.com", kind = "Zed"), isFavourite = false),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Favourite CRDs").assertIsDisplayed()
        composeRule.onNodeWithText("All CRDs").assertIsDisplayed()
    }

    @Test
    fun test_CrdListScreen_refreshClick_invokesRetryCallback() {
        var refreshClicked = false
        setScreen(
            state = CrdListScreenState(listState = ResourceListUiState.Empty),
            onRetryClick = { refreshClicked = true },
        )

        composeRule.onNodeWithContentDescription("Refresh custom resource definitions").performClick()

        composeRule.runOnIdle {
            assertTrue(refreshClicked)
        }
    }

    @Test
    fun test_CrdListScreen_starClick_invertsExpectedBoolean() {
        val crd = sampleCrd(name = "widgets.example.com", kind = "Widget")
        var callbackCrd: CustomResourceDefinition? = null
        var callbackFavourite: Boolean? = null
        setScreen(
            state = CrdListScreenState(
                listState = ResourceListUiState.Success(
                    items = listOf(CrdListItem(definition = crd, isFavourite = false)),
                ),
            ),
            onToggleFavourite = { selectedCrd, favourite ->
                callbackCrd = selectedCrd
                callbackFavourite = favourite
            },
        )

        composeRule.onNodeWithContentDescription("Add to favourites").performClick()

        composeRule.runOnIdle {
            assertEquals(crd, callbackCrd)
            assertEquals(true, callbackFavourite)
        }
    }

    @Test
    fun test_CrdListScreen_rowClick_invokesCrdCallback() {
        val crd = sampleCrd(name = "widgets.example.com", kind = "Widget")
        var clickedCrd: CustomResourceDefinition? = null
        setScreen(
            state = CrdListScreenState(
                listState = ResourceListUiState.Success(
                    items = listOf(CrdListItem(definition = crd, isFavourite = false)),
                ),
            ),
            onCrdClick = { clickedCrd = it },
        )

        composeRule.onNodeWithText("widgets.example.com").performClick()

        composeRule.runOnIdle {
            assertEquals(crd, clickedCrd)
        }
    }

    private fun setScreen(
        state: CrdListScreenState,
        onQueryChanged: (String) -> Unit = {},
        onRetryClick: () -> Unit = {},
        onToggleFavourite: (CustomResourceDefinition, Boolean) -> Unit = { _, _ -> },
        onCrdClick: (CustomResourceDefinition) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                CrdListScreen(
                    state = state,
                    onQueryChanged = onQueryChanged,
                    onRetryClick = onRetryClick,
                    onToggleFavourite = onToggleFavourite,
                    onCrdClick = onCrdClick,
                )
            }
        }
    }

    private fun sampleCrd(
        name: String,
        kind: String,
        scope: String = "Namespaced",
    ): CustomResourceDefinition {
        return CustomResourceDefinition(
            name = name,
            group = "example.com",
            version = "v1",
            scope = scope,
            kind = kind,
        )
    }
}
