package com.kubedroid.feature.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kubedroid.core.network.connection.ClusterConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AddClusterScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_validInput_enablesSubmitButtons() {
        setKubeconfigScreen(kubeConfigText = "apiVersion: v1")

        composeRule.onNodeWithContentDescription("Save cluster configuration").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Connect to cluster").assertIsEnabled()
    }

    @Test
    fun test_invalidInput_disablesSubmitButtons() {
        composeRule.setContent {
            MaterialTheme {
                AddClusterScreen(
                    inputMode = AddClusterInputMode.MANUAL,
                    kubeConfigText = "",
                    server = "https://cluster.example.com",
                    token = "",
                    insecureTlsEnabled = false,
                    showInsecureTlsWarningDialog = false,
                    connectionState = ClusterConnectionState.Disconnected,
                    onInputModeChange = {},
                    onKubeConfigTextChange = {},
                    onServerChange = {},
                    onTokenChange = {},
                    onInsecureTlsToggleRequested = {},
                    onConfirmEnableInsecureTls = {},
                    onDismissInsecureTlsWarning = {},
                    onSaveClick = {},
                    onCancelClick = {},
                    onConnectClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Save cluster configuration").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Connect to cluster").assertIsNotEnabled()
    }

    @Test
    fun test_submitSuccess_invokesSaveAndConnectCallbacks() {
        var saveClicks = 0
        var connectClicks = 0
        var editedText = ""

        composeRule.setContent {
            MaterialTheme {
                AddClusterScreen(
                    inputMode = AddClusterInputMode.KUBECONFIG,
                    kubeConfigText = "apiVersion: v1",
                    server = "",
                    token = "",
                    insecureTlsEnabled = false,
                    showInsecureTlsWarningDialog = false,
                    connectionState = ClusterConnectionState.Disconnected,
                    onInputModeChange = {},
                    onKubeConfigTextChange = { editedText = it },
                    onServerChange = {},
                    onTokenChange = {},
                    onInsecureTlsToggleRequested = {},
                    onConfirmEnableInsecureTls = {},
                    onDismissInsecureTlsWarning = {},
                    onSaveClick = { saveClicks++ },
                    onCancelClick = {},
                    onConnectClick = { connectClicks++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Kubeconfig input").performTextInput("\nkind: Config")
        composeRule.onNodeWithContentDescription("Save cluster configuration").performClick()
        composeRule.onNodeWithContentDescription("Connect to cluster").performClick()

        composeRule.runOnIdle {
            assertEquals(1, saveClicks)
            assertEquals(1, connectClicks)
            assertTrue(editedText.contains("kind: Config"))
        }
    }

    @Test
    fun test_submitError_showsFailedStateReason() {
        composeRule.setContent {
            MaterialTheme {
                AddClusterScreen(
                    inputMode = AddClusterInputMode.KUBECONFIG,
                    kubeConfigText = "apiVersion: v1",
                    server = "",
                    token = "",
                    insecureTlsEnabled = false,
                    showInsecureTlsWarningDialog = false,
                    connectionState = ClusterConnectionState.Failed(
                        contextName = "dev",
                        reason = "invalid token",
                    ),
                    onInputModeChange = {},
                    onKubeConfigTextChange = {},
                    onServerChange = {},
                    onTokenChange = {},
                    onInsecureTlsToggleRequested = {},
                    onConfirmEnableInsecureTls = {},
                    onDismissInsecureTlsWarning = {},
                    onSaveClick = {},
                    onCancelClick = {},
                    onConnectClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Failed to connect to dev").assertIsDisplayed()
        composeRule.onNodeWithText("invalid token").assertIsDisplayed()
    }

    private fun setKubeconfigScreen(kubeConfigText: String) {
        composeRule.setContent {
            MaterialTheme {
                AddClusterScreen(
                    inputMode = AddClusterInputMode.KUBECONFIG,
                    kubeConfigText = kubeConfigText,
                    server = "",
                    token = "",
                    insecureTlsEnabled = false,
                    showInsecureTlsWarningDialog = false,
                    connectionState = ClusterConnectionState.Disconnected,
                    onInputModeChange = {},
                    onKubeConfigTextChange = {},
                    onServerChange = {},
                    onTokenChange = {},
                    onInsecureTlsToggleRequested = {},
                    onConfirmEnableInsecureTls = {},
                    onDismissInsecureTlsWarning = {},
                    onSaveClick = {},
                    onCancelClick = {},
                    onConnectClick = {},
                )
            }
        }
    }
}
