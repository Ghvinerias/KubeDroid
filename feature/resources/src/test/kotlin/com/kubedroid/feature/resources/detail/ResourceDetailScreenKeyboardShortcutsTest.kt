package com.kubedroid.feature.resources.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import com.kubedroid.core.network.resources.KubeEvent
import com.kubedroid.core.network.resources.ResourceDetail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResourceDetailScreenKeyboardShortcutsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun keyboardShortcuts_triggerExpectedActions() {
        var selectedTab = ResourceDetailTab.YAML
        var logsCount = 0
        var execCount = 0
        var refreshCount = 0
        var editedYaml: String? = null

        val state = ResourceDetailUiState.Content(
            detail = sampleDetail(),
            yamlDraft = "apiVersion: v1\nkind: Pod\nmetadata:\n  name: demo-updated",
        )

        composeRule.setContent {
            val focusRequester = FocusRequester()
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            MaterialTheme {
                ResourceDetailScreen(
                    state = state,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    onEditYamlClick = { editedYaml = it },
                    onLogsClick = { logsCount += 1 },
                    onExecClick = { execCount += 1 },
                    onBackClick = {},
                    onRetryClick = {},
                    onRefreshClick = { refreshCount += 1 },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .focusable(),
                )
            }
        }

        composeRule.onRoot().performKeyInput {
            keyDown(Key.D)
            keyUp(Key.D)
            keyDown(Key.L)
            keyUp(Key.L)
            keyDown(Key.E)
            keyUp(Key.E)
            keyDown(Key.S)
            keyUp(Key.S)
            keyDown(Key.R)
            keyUp(Key.R)
        }

        composeRule.runOnIdle {
            assertEquals(ResourceDetailTab.DESCRIBE, selectedTab)
            assertEquals(1, logsCount)
            assertEquals(1, execCount)
            assertEquals(1, refreshCount)
            assertEquals(state.yamlDraft, editedYaml)
        }
    }

    private fun sampleDetail(): ResourceDetail = ResourceDetail(
        name = "demo",
        namespace = "default",
        kind = "Pod",
        apiVersion = "v1",
        resourceVersion = "12",
        yaml = "apiVersion: v1\nkind: Pod\nmetadata:\n  name: demo",
        events = listOf(
            KubeEvent(
                reason = "Started",
                type = "Normal",
                message = "Container started",
                source = "kubelet",
                count = 1,
                firstTimestampEpochMillis = 1_700_000_000_000,
                lastTimestampEpochMillis = 1_700_000_010_000,
            ),
        ),
    )
}
