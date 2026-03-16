package com.kubedroid.feature.helm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HelmRollbackDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_HelmRollbackDialog_emptyRevisions_showsEmptyAndDisablesConfirm() {
        setDialog(revisions = emptyList())

        composeRule.onNodeWithText("Rollback demo").assertIsDisplayed()
        composeRule.onNodeWithText("No revisions available for rollback").assertIsDisplayed()
        composeRule.onNodeWithText("Rollback").assertIsNotEnabled()
    }

    @Test
    fun test_HelmRollbackDialog_defaultSelection_confirmsFirstRevision() {
        var confirmedRevision: Int? = null
        setDialog(
            revisions = revisions(),
            onConfirmRollback = { confirmedRevision = it },
        )

        composeRule.onNodeWithText("Select a revision to rollback to.").assertIsDisplayed()
        composeRule.onNodeWithText("Revision 3").assertIsDisplayed()
        composeRule.onNodeWithText("Rollback").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(3, confirmedRevision)
        }
    }

    @Test
    fun test_HelmRollbackDialog_selectDifferentRevision_confirmsSelectedRevision() {
        var confirmedRevision: Int? = null
        setDialog(
            revisions = revisions(),
            onConfirmRollback = { confirmedRevision = it },
        )

        composeRule.onNodeWithText("previous").performClick()
        composeRule.onNodeWithText("Rollback").performClick()

        composeRule.runOnIdle {
            assertEquals(3, confirmedRevision)
        }
    }

    @Test
    fun test_HelmRollbackDialog_cancel_invokesDismiss() {
        var dismissed = false
        setDialog(
            revisions = revisions(),
            onDismissRequest = { dismissed = true },
        )

        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.runOnIdle {
            assertTrue(dismissed)
        }
    }

    private fun setDialog(
        revisions: List<HelmRevisionUiModel>,
        onConfirmRollback: (Int) -> Unit = {},
        onDismissRequest: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                HelmRollbackDialog(
                    releaseName = "demo",
                    revisions = revisions,
                    onConfirmRollback = onConfirmRollback,
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }

    private fun revisions(): List<HelmRevisionUiModel> {
        return listOf(
            HelmRevisionUiModel(
                revision = 3,
                status = HelmReleaseStatus.DEPLOYED,
                description = "current",
                deployedAtEpochMillis = null,
            ),
            HelmRevisionUiModel(
                revision = 2,
                status = HelmReleaseStatus.SUPERSEDED,
                description = "previous",
                deployedAtEpochMillis = null,
            ),
        )
    }
}
