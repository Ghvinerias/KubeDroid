package com.kubedroid.feature.onboarding.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kubedroid.core.network.connection.ClusterConnectionState
import com.kubedroid.feature.onboarding.ImportOption
import com.kubedroid.feature.onboarding.OnboardingState
import com.kubedroid.feature.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingRouteTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_importPage_allImportOptionsCanBeSelected() {
        var selectedImportOption = ImportOption.PasteText
        var pickFileClicks = 0
        var scanQrClicks = 0

        composeRule.setContent {
            var state by mutableStateOf(
                OnboardingState(
                    isCheckingCompletion = false,
                    currentStep = OnboardingStep.ImportKubeconfig,
                ),
            )
            MaterialTheme {
                OnboardingRoute(
                    state = state,
                    onStepChanged = { step -> state = state.copy(currentStep = step) },
                    onContinueFromWelcome = {},
                    onImportOptionSelected = { option ->
                        selectedImportOption = option
                        state = state.copy(selectedImportOption = option)
                    },
                    onPickFileClick = { pickFileClicks += 1 },
                    onScanQrClick = { scanQrClicks += 1 },
                    onKubeconfigInputChange = {},
                    onSaveKubeconfig = {},
                    onTestConnection = {},
                    onFinish = {},
                )
            }
        }

        composeRule.onNodeWithText("Kubeconfig").assertIsDisplayed()

        composeRule.onNodeWithText("Pick file").performClick()
        composeRule.onNodeWithText(
            "This import method will be connected in a follow-up step. You can continue with paste text now.",
        ).assertIsDisplayed()

        composeRule.onNodeWithText("Scan QR").performClick()
        composeRule.onNodeWithText("Paste text").performClick()
        composeRule.onNodeWithText("Kubeconfig").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(ImportOption.PasteText, selectedImportOption)
            assertEquals(1, pickFileClicks)
            assertEquals(1, scanQrClicks)
        }
    }

    @Test
    fun test_testConnectionPage_connectedState_showsSuccessMessage() {
        setScreen(
            OnboardingState(
                isCheckingCompletion = false,
                currentStep = OnboardingStep.TestConnection,
                connectionState = ClusterConnectionState.Connected("dev"),
            ),
        )

        composeRule.onNodeWithText("Connected to dev successfully.").assertIsDisplayed()
    }

    @Test
    fun test_testConnectionPage_failedState_showsFailureMessage() {
        setScreen(
            OnboardingState(
                isCheckingCompletion = false,
                currentStep = OnboardingStep.TestConnection,
                connectionState = ClusterConnectionState.Failed(
                    contextName = "dev",
                    reason = "TLS handshake timeout",
                ),
            ),
        )

        composeRule.onNodeWithText("Connection failed: TLS handshake timeout").assertIsDisplayed()
    }

    private fun setScreen(state: OnboardingState) {
        composeRule.setContent {
            MaterialTheme {
                OnboardingRoute(
                    state = state,
                    onStepChanged = {},
                    onContinueFromWelcome = {},
                    onImportOptionSelected = {},
                    onPickFileClick = {},
                    onScanQrClick = {},
                    onKubeconfigInputChange = {},
                    onSaveKubeconfig = {},
                    onTestConnection = {},
                    onFinish = {},
                )
            }
        }
    }
}
