package com.kubedroid.feature.pods.portforward.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.kubedroid.feature.pods.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartPortForwardSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun startButton_disabledWhenInputIsInvalid() {
        setSheet(
            state = StartPortForwardSheetUiState(
                pods = listOf("api-123"),
                selectedPod = "api-123",
                remotePortInput = "70000",
                localPortInput = "",
            ),
        )

        composeRule.onNodeWithText(stringResource(R.string.port_forward_sheet_start)).assertIsNotEnabled()
        composeRule.onNodeWithText(stringResource(R.string.port_forward_sheet_port_invalid)).assertIsDisplayed()
    }

    @Test
    fun startButton_enabledWhenInputIsValid_andInvokesCallback() {
        var startClicked = false
        setSheet(
            state = StartPortForwardSheetUiState(
                pods = listOf("api-123"),
                selectedPod = "api-123",
                remotePortInput = "8080",
                localPortInput = "0",
            ),
            onStartClick = { startClicked = true },
        )

        composeRule.onNodeWithText(stringResource(R.string.port_forward_sheet_start)).assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertTrue(startClicked)
        }
    }

    @Test
    fun selectingPodFromDropdown_invokesCallback() {
        var selectedPod: String? = null
        setSheet(
            state = StartPortForwardSheetUiState(
                pods = listOf("api-123", "api-456"),
                selectedPod = null,
                remotePortInput = "",
                localPortInput = "",
            ),
            onPodSelected = { selectedPod = it },
        )

        composeRule
            .onNodeWithText(stringResource(R.string.port_forward_sheet_pod_placeholder))
            .performClick()
        composeRule.onNodeWithText("api-456").performClick()

        composeRule.runOnIdle {
            assertEquals("api-456", selectedPod)
        }
    }

    @Test
    fun remotePortField_filtersNonDigitsBeforeCallback() {
        var callbackValue: String? = null
        setSheet(
            state = StartPortForwardSheetUiState(
                pods = listOf("api-123"),
                selectedPod = "api-123",
                remotePortInput = "",
                localPortInput = "",
            ),
            onRemotePortInputChange = { callbackValue = it },
        )

        composeRule
            .onNodeWithText(stringResource(R.string.port_forward_sheet_remote_port_label))
            .performTextInput("80abc")

        composeRule.runOnIdle {
            assertEquals("80", callbackValue)
        }
    }

    private fun setSheet(
        state: StartPortForwardSheetUiState,
        onDismissRequest: () -> Unit = {},
        onPodSelected: (String) -> Unit = {},
        onLocalPortInputChange: (String) -> Unit = {},
        onRemotePortInputChange: (String) -> Unit = {},
        onStartClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                StartPortForwardSheet(
                    state = state,
                    onDismissRequest = onDismissRequest,
                    onPodSelected = onPodSelected,
                    onLocalPortInputChange = onLocalPortInputChange,
                    onRemotePortInputChange = onRemotePortInputChange,
                    onStartClick = onStartClick,
                )
            }
        }
    }

    private fun stringResource(@StringRes id: Int, vararg formatArgs: Any): String {
        return appContext.getString(id, *formatArgs)
    }
}
