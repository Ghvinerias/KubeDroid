package com.kubedroid.feature.helm.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kubedroid.feature.helm.model.HelmRelease
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HelmListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_HelmListScreen_loadingState_rendersProgress() {
        setScreen(state = HelmUiState.Loading)

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun test_HelmListScreen_listState_rendersReleaseAndClickCallback() {
        var clickedName: String? = null
        setScreen(
            state = HelmUiState.Success(
                releases = listOf(
                    HelmRelease(
                        name = "demo",
                        namespace = "prod",
                        chart = "nginx",
                        chartVersion = "1.2.3",
                        appVersion = "9.8.7",
                        status = "deployed",
                        lastDeployed = null,
                    ),
                ),
            ),
            onReleaseClick = { clickedName = it.name },
        )

        composeRule.onNodeWithText("Helm Releases").assertIsDisplayed()
        composeRule.onNodeWithText("demo").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Chart: nginx").assertIsDisplayed()
        composeRule.onNodeWithText("Version: 9.8.7").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals("demo", clickedName)
        }
    }

    @Test
    fun test_HelmListScreen_emptyState_rendersEmptyText() {
        setScreen(state = HelmUiState.Empty)

        composeRule.onNodeWithText("No Helm releases found").assertIsDisplayed()
    }

    @Test
    fun test_HelmListScreen_errorState_rendersCauseMessage() {
        setScreen(state = HelmUiState.Error(cause = IllegalStateException("Forbidden")))

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
    }

    @Test
    fun test_HelmListScreen_errorState_withoutCause_rendersFallbackMessage() {
        setScreen(state = HelmUiState.Error(cause = null))

        composeRule.onNodeWithText("Unable to load Helm releases").assertIsDisplayed()
    }

    private fun setScreen(
        state: HelmUiState,
        onReleaseClick: (HelmReleaseUiModel) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                HelmListScreenStateHost(
                    state = state,
                    onReleaseClick = onReleaseClick,
                )
            }
        }
    }
}

@Composable
private fun HelmListScreenStateHost(
    state: HelmUiState,
    onReleaseClick: (HelmReleaseUiModel) -> Unit,
) {
    when (state) {
        HelmUiState.Loading -> CircularProgressIndicator()
        HelmUiState.Empty -> HelmListScreen(
            releases = emptyList(),
            selectedNamespace = "default",
            namespaces = listOf("default"),
            onNamespaceSelected = {},
            onReleaseClick = onReleaseClick,
        )
        is HelmUiState.Success -> HelmListScreen(
            releases = state.releases.map { release ->
                HelmReleaseUiModel(
                    name = release.name,
                    chart = release.chart,
                    version = release.appVersion,
                    status = mapStatus(release.status),
                    lastDeployedEpochMillis = release.lastDeployed,
                )
            },
            selectedNamespace = "default",
            namespaces = listOf("default"),
            onNamespaceSelected = {},
            onReleaseClick = onReleaseClick,
        )
        is HelmUiState.Error -> androidx.compose.material3.Text(
            text = state.cause?.message ?: "Unable to load Helm releases",
        )
    }
}

private fun mapStatus(status: String): HelmReleaseStatus {
    return when (status.lowercase()) {
        "deployed" -> HelmReleaseStatus.DEPLOYED
        "failed" -> HelmReleaseStatus.FAILED
        "pending_install", "pending_upgrade", "pending_rollback" -> HelmReleaseStatus.PENDING
        "superseded" -> HelmReleaseStatus.SUPERSEDED
        "uninstalled" -> HelmReleaseStatus.UNINSTALLED
        "uninstalling" -> HelmReleaseStatus.UNINSTALLING
        else -> HelmReleaseStatus.UNKNOWN
    }
}
