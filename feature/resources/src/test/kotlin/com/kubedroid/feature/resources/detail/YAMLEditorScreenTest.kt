package com.kubedroid.feature.resources.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.kubedroid.core.network.resources.YamlEditResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YAMLEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_YAMLEditorScreen_actionsInvokeCallbacksAndTextChangeReportsDraft() {
        var backClicked = false
        var applyClicked = false
        var capturedDraft: String? = null
        setScreen(
            yamlDraft = "apiVersion: v1",
            onBackClick = { backClicked = true },
            onApplyClick = { applyClicked = true },
            onYamlDraftChange = { capturedDraft = it },
        )

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Apply").performClick()
        composeRule.onNodeWithText("apiVersion: v1").performTextReplacement("kind: Pod")

        composeRule.runOnIdle {
            assertTrue(backClicked)
            assertTrue(applyClicked)
            assertEquals("kind: Pod", capturedDraft)
        }
    }

    @Test
    fun test_YAMLEditorScreen_isApplying_disablesApplyAndShowsApplyingStatus() {
        setScreen(
            yamlDraft = "apiVersion: v1",
            isApplying = true,
        )

        composeRule.onNodeWithText("Apply").assertIsNotEnabled()
        composeRule.onNodeWithText("Applying YAML changes...").assertIsDisplayed()
    }

    @Test
    fun test_YAMLEditorScreen_conflictResult_showsConflictMessageWithVersion() {
        setScreen(
            yamlDraft = "apiVersion: v1",
            lastYamlEditResult = YamlEditResult.Conflict(currentResourceVersion = "42"),
        )

        composeRule.onNodeWithText("Conflict detected. Current resource version: 42").assertIsDisplayed()
    }

    @Test
    fun test_YAMLEditorScreen_errorWithoutMessage_showsGenericError() {
        setScreen(
            yamlDraft = "apiVersion: v1",
            applyError = IllegalStateException(),
        )

        composeRule.onNodeWithText("Failed to apply YAML").assertIsDisplayed()
    }

    private fun setScreen(
        yamlDraft: String,
        isApplying: Boolean = false,
        lastYamlEditResult: YamlEditResult? = null,
        applyError: Throwable? = null,
        onYamlDraftChange: (String) -> Unit = {},
        onApplyClick: () -> Unit = {},
        onBackClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                YAMLEditorScreen(
                    yamlDraft = yamlDraft,
                    isApplying = isApplying,
                    lastYamlEditResult = lastYamlEditResult,
                    applyError = applyError,
                    onYamlDraftChange = onYamlDraftChange,
                    onApplyClick = onApplyClick,
                    onBackClick = onBackClick,
                )
            }
        }
    }
}
