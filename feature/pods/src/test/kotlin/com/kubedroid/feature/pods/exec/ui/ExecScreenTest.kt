package com.kubedroid.feature.pods.exec.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
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
class ExecScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleState_rendersIdleStatus() {
        setScreen(state = ExecUiState.Idle)

        composeRule.onNodeWithText("Idle").assertIsDisplayed()
    }

    @Test
    fun connectingState_rendersConnectingStatus() {
        setScreen(state = ExecUiState.Connecting)

        composeRule.onNodeWithText("Connecting...").assertIsDisplayed()
    }

    @Test
    fun activeState_rendersConnectedStatus() {
        setScreen(state = ExecUiState.Active)

        composeRule.onNodeWithText("Connected").assertIsDisplayed()
    }

    @Test
    fun exitedState_rendersExitCodeStatus() {
        setScreen(state = ExecUiState.Exited(130))

        composeRule.onNodeWithText("Session exited with code 130").assertIsDisplayed()
    }

    @Test
    fun errorState_withMessage_rendersMessage() {
        setScreen(state = ExecUiState.Error("forbidden"))

        composeRule.onNodeWithText("Error: forbidden").assertIsDisplayed()
    }

    @Test
    fun errorState_withoutMessage_rendersUnknownFallback() {
        setScreen(state = ExecUiState.Error(null))

        composeRule.onNodeWithText("Error: Unknown error").assertIsDisplayed()
    }

    @Test
    fun backButton_invokesCallback() {
        var backClicked = false
        setScreen(
            state = ExecUiState.Active,
            onBackClick = { backClicked = true },
        )

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.runOnIdle { assertTrue(backClicked) }
    }

    @Test
    fun disconnectButton_invokesCallback() {
        var disconnectClicked = false
        setScreen(
            state = ExecUiState.Active,
            onDisconnectClick = { disconnectClicked = true },
        )

        composeRule.onNodeWithContentDescription("Disconnect exec session").performClick()

        composeRule.runOnIdle { assertTrue(disconnectClicked) }
    }

    private fun setScreen(
        state: ExecUiState,
        onBackClick: () -> Unit = {},
        onDisconnectClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ExecScreen(
                    state = state,
                    onBackClick = onBackClick,
                    onDisconnectClick = onDisconnectClick,
                    onTerminalInput = {},
                    onTerminalResize = { _, _ -> },
                    onWebViewReady = {},
                    onWebViewReleased = {},
                    terminalContent = { modifier: Modifier -> Box(modifier = modifier) },
                )
            }
        }
    }
}
