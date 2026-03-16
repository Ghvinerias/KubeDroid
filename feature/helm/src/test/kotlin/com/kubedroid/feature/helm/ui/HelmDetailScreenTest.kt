package com.kubedroid.feature.helm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HelmDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_HelmDetailScreen_infoTab_rendersInfoItems() {
        setScreen(
            selectedTab = HelmDetailTab.INFO,
            infoItems = listOf("Namespace" to "prod", "Status" to "deployed"),
        )

        composeRule.onNodeWithText("demo").assertIsDisplayed()
        composeRule.onNodeWithText("Namespace").assertIsDisplayed()
        composeRule.onNodeWithText("prod").assertIsDisplayed()
        composeRule.onNodeWithText("Status").assertIsDisplayed()
    }

    @Test
    fun test_HelmDetailScreen_historyTab_rendersRevisions() {
        setScreen(
            selectedTab = HelmDetailTab.HISTORY,
            revisions = listOf(
                HelmRevisionUiModel(
                    revision = 3,
                    status = HelmReleaseStatus.DEPLOYED,
                    description = "upgrade",
                    deployedAtEpochMillis = null,
                ),
            ),
        )

        composeRule.onNodeWithText("Revision 3").assertIsDisplayed()
        composeRule.onNodeWithText("upgrade").assertIsDisplayed()
    }

    @Test
    fun test_HelmDetailScreen_manifestTab_rendersManifest() {
        setScreen(
            selectedTab = HelmDetailTab.MANIFEST,
            manifest = "kind: Deployment\nmetadata:\n  name: demo",
        )

        composeRule.onNodeWithText("kind: Deployment\nmetadata:\n  name: demo").assertIsDisplayed()
    }

    @Test
    fun test_HelmDetailScreen_tabClicks_invokeCallback() {
        var selectedTab: HelmDetailTab? = null
        setScreen(
            selectedTab = HelmDetailTab.INFO,
            onTabSelected = { selectedTab = it },
        )

        composeRule.onNodeWithText("History").performClick()
        composeRule.runOnIdle {
            assertEquals(HelmDetailTab.HISTORY, selectedTab)
        }

        composeRule.onNodeWithText("Manifest").performClick()
        composeRule.runOnIdle {
            assertEquals(HelmDetailTab.MANIFEST, selectedTab)
        }
    }

    private fun setScreen(
        selectedTab: HelmDetailTab,
        infoItems: List<Pair<String, String>> = emptyList(),
        revisions: List<HelmRevisionUiModel> = emptyList(),
        manifest: String = "",
        onTabSelected: (HelmDetailTab) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                HelmDetailScreen(
                    releaseName = "demo",
                    selectedTab = selectedTab,
                    infoItems = infoItems,
                    revisions = revisions,
                    manifest = manifest,
                    onTabSelected = onTabSelected,
                )
            }
        }
    }
}
