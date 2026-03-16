package com.kubedroid.feature.network.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_NetworkScreen_ingressesTabSelected_rendersIngressState() {
        setScreen(
            state = NetworkUiState(
                selectedTab = NetworkTab.INGRESSES,
                ingressState = NetworkListState.Empty,
                networkPolicyState = NetworkListState.Empty,
            ),
        )

        composeRule.onNodeWithText("Ingresses").assertIsDisplayed()
        composeRule.onNodeWithText("Namespace").assertIsDisplayed()
        composeRule.onNodeWithText("No ingresses found").assertIsDisplayed()
    }

    @Test
    fun test_NetworkScreen_tabSelectionSwitchesRenderedContent() {
        setScreen(
            state = NetworkUiState(
                selectedTab = NetworkTab.NETWORK_POLICIES,
                ingressState = NetworkListState.Empty,
                networkPolicyState = NetworkListState.Empty,
            ),
        )

        composeRule.onAllNodesWithText("No network policies found").assertCountEquals(1)
        composeRule.onAllNodesWithText("No ingresses found").assertCountEquals(0)
    }

    @Test
    fun test_NetworkScreen_tabClick_invokesSelectionCallback() {
        var selectedTab: NetworkTab? = null
        setScreen(
            state = NetworkUiState(
                selectedTab = NetworkTab.INGRESSES,
                ingressState = NetworkListState.Empty,
                networkPolicyState = NetworkListState.Empty,
            ),
            onTabSelected = { selectedTab = it },
        )

        composeRule.onNodeWithText("NetworkPolicies").performClick()

        composeRule.runOnIdle {
            assertEquals(NetworkTab.NETWORK_POLICIES, selectedTab)
        }
    }

    private fun setScreen(
        state: NetworkUiState,
        onTabSelected: (NetworkTab) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                NetworkScreen(
                    state = state,
                    onTabSelected = onTabSelected,
                    onRefresh = {},
                    onNamespaceChange = {},
                    onIngressClick = { _, _ -> },
                    onCopyUrl = {},
                )
            }
        }
    }
}
