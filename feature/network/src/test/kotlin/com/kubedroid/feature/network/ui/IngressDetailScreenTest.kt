package com.kubedroid.feature.network.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IngressDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_IngressDetailScreen_successState_rendersRoutingTree() {
        setScreen(
            IngressDetailState(
                namespace = "default",
                ingressName = "edge",
                contentState = IngressDetailContentState.Success(
                    detail = IngressDetailUi(
                        name = "edge",
                        namespace = "default",
                        routes = listOf(
                            IngressHostRouteUi(
                                host = "app.example.com",
                                paths = listOf(
                                    IngressPathRouteUi(
                                        path = "/api",
                                        serviceName = "api-svc",
                                        servicePort = "8080",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Routing").assertIsDisplayed()
        composeRule.onNodeWithText("- Host: app.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("- Path: /api").assertIsDisplayed()
        composeRule.onNodeWithText("Service: api-svc").assertIsDisplayed()
        composeRule.onNodeWithText("Port: 8080").assertIsDisplayed()
    }

    @Test
    fun test_IngressDetailScreen_successState_rendersFallbackValues() {
        setScreen(
            IngressDetailState(
                namespace = "default",
                ingressName = "edge",
                contentState = IngressDetailContentState.Success(
                    detail = IngressDetailUi(
                        name = "edge",
                        namespace = "default",
                        routes = listOf(
                            IngressHostRouteUi(
                                host = null,
                                paths = listOf(
                                    IngressPathRouteUi(
                                        path = "/",
                                        serviceName = "",
                                        servicePort = null,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("- Host: All hosts").assertIsDisplayed()
        composeRule.onNodeWithText("Service: Unknown service").assertIsDisplayed()
        composeRule.onNodeWithText("Port: Unknown").assertIsDisplayed()
    }

    @Test
    fun test_IngressDetailScreen_successState_withNoRoutes_rendersEmptyMessage() {
        setScreen(
            IngressDetailState(
                namespace = "default",
                ingressName = "edge",
                contentState = IngressDetailContentState.Success(
                    detail = IngressDetailUi(
                        name = "edge",
                        namespace = "default",
                        routes = emptyList(),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("No host/path routes available").assertIsDisplayed()
    }

    @Test
    fun test_IngressDetailScreen_actions_invokeCallbacks() {
        var backClicked = false
        var refreshClicked = false
        setScreen(
            state = IngressDetailState(
                namespace = "default",
                ingressName = "edge",
                contentState = IngressDetailContentState.Loading,
            ),
            onBackClick = { backClicked = true },
            onRefresh = { refreshClicked = true },
        )

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Refresh networking").performClick()

        composeRule.runOnIdle {
            assertTrue(backClicked)
            assertTrue(refreshClicked)
        }
    }

    private fun setScreen(
        state: IngressDetailState,
        onBackClick: () -> Unit = {},
        onRefresh: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                IngressDetailScreen(
                    state = state,
                    onBackClick = onBackClick,
                    onRefresh = onRefresh,
                )
            }
        }
    }
}
