package com.kubedroid.feature.pods.portforward.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.kubedroid.feature.pods.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PortForwardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun emptyState_rendersMessage() {
        setScreen(sessions = emptyList(), isStartSheetVisible = false)

        composeRule
            .onNodeWithText(stringResource(R.string.port_forward_empty))
            .assertIsDisplayed()
    }

    @Test
    fun sessionRow_rendersActions_andInvokesCallbacks() {
        var copied: String? = null
        var opened: String? = null
        var closed: String? = null

        setScreen(
            sessions = listOf(
                PortForwardSessionUiModel(
                    id = "s1",
                    namespace = "default",
                    podName = "api-123",
                    localPort = 18080,
                    remotePort = 80,
                    status = PortForwardSessionUiStatus.ACTIVE,
                ),
            ),
            isStartSheetVisible = false,
            onCopy = { copied = it },
            onOpen = { opened = it },
            onClose = { closed = it },
        )

        composeRule.onNodeWithText(stringResource(R.string.port_forward_copy_url)).performClick()
        composeRule.onNodeWithText(stringResource(R.string.port_forward_open_in_browser)).performClick()
        composeRule.onNodeWithContentDescription(stringResource(R.string.port_forward_close_session_cd)).performClick()

        composeRule.runOnIdle {
            val expectedUrl = stringResource(R.string.port_forward_local_url_template, 18080)
            assertEquals(expectedUrl, copied)
            assertEquals(expectedUrl, opened)
            assertEquals("s1", closed)
        }
    }

    @Test
    fun topBarStartAction_invokesCallback() {
        var openSheetClicked = false
        setScreen(
            sessions = emptyList(),
            isStartSheetVisible = false,
            onOpenStartSheet = { openSheetClicked = true },
        )

        composeRule
            .onNodeWithContentDescription(stringResource(R.string.port_forward_start_cd))
            .performClick()

        composeRule.runOnIdle { assertTrue(openSheetClicked) }
    }

    @Test
    fun startPortForwardSheet_startDisabledWhenRemotePortMissing() {
        setStartSheet(
            state = StartPortForwardSheetUiState(
                pods = listOf("api-123"),
                selectedPod = "api-123",
                remotePortInput = "",
                localPortInput = "",
            ),
        )

        composeRule
            .onNodeWithText(stringResource(R.string.port_forward_sheet_start))
            .assertIsNotEnabled()
    }

    @Test
    fun startPortForwardSheet_podSelectionAndStart_invokeCallbacks() {
        var selectedPod: String? = null
        var startClicked = false

        setStartSheet(
            state = StartPortForwardSheetUiState(
                pods = listOf("api-123", "worker-456"),
                selectedPod = "api-123",
                remotePortInput = "8080",
                localPortInput = "",
            ),
            onPodSelected = { selectedPod = it },
            onStartClick = { startClicked = true },
        )

        composeRule.onNodeWithText("api-123").performClick()
        composeRule.onNodeWithText("worker-456").performClick()
        composeRule.onNodeWithText(stringResource(R.string.port_forward_sheet_start)).performClick()

        composeRule.runOnIdle {
            assertEquals("worker-456", selectedPod)
            assertTrue(startClicked)
        }
    }

    private fun setScreen(
        sessions: List<PortForwardSessionUiModel>,
        isStartSheetVisible: Boolean,
        startSheetState: StartPortForwardSheetUiState = StartPortForwardSheetUiState(),
        onCopy: (String) -> Unit = {},
        onOpen: (String) -> Unit = {},
        onClose: (String) -> Unit = {},
        onStart: () -> Unit = {},
        onOpenStartSheet: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PortForwardScreen(
                    sessions = sessions,
                    isStartSheetVisible = isStartSheetVisible,
                    startSheetState = startSheetState,
                    onBackClick = {},
                    onOpenStartSheetClick = onOpenStartSheet,
                    onDismissStartSheet = {},
                    onPodSelected = {},
                    onLocalPortInputChange = {},
                    onRemotePortInputChange = {},
                    onStartPortForwardClick = onStart,
                    onCloseSessionClick = onClose,
                    onCopyUrlClick = onCopy,
                    onOpenInBrowserClick = onOpen,
                )
            }
        }
    }

    private fun stringResource(@StringRes id: Int, vararg formatArgs: Any): String {
        return appContext.getString(id, *formatArgs)
    }

    private fun setStartSheet(
        state: StartPortForwardSheetUiState,
        onPodSelected: (String) -> Unit = {},
        onStartClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                StartPortForwardSheet(
                    state = state,
                    onDismissRequest = {},
                    onPodSelected = onPodSelected,
                    onLocalPortInputChange = {},
                    onRemotePortInputChange = {},
                    onStartClick = onStartClick,
                )
            }
        }
    }
}
