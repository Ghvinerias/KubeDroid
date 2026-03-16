package com.kubedroid.feature.deployments.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScaleDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test_ScaleDialog_rendersTitleButtonsAndReplicaValue() {
        setScreen(replicas = 3)

        composeRule.onNodeWithText("Scale deployment").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("Apply").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Decrease replicas").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Increase replicas").assertIsDisplayed()
    }

    @Test
    fun test_ScaleDialog_decreaseAndIncrease_invokeCallbacks() {
        var decreaseClicks = 0
        var increaseClicks = 0
        setScreen(
            replicas = 2,
            onDecreaseClick = { decreaseClicks += 1 },
            onIncreaseClick = { increaseClicks += 1 },
        )

        composeRule.onNodeWithContentDescription("Decrease replicas").performClick()
        composeRule.onNodeWithContentDescription("Increase replicas").performClick()

        composeRule.runOnIdle {
            assertEquals(1, decreaseClicks)
            assertEquals(1, increaseClicks)
        }
    }

    @Test
    fun test_ScaleDialog_confirmAndCancel_invokeCallbacks() {
        var confirmClicked = false
        var dismissCount = 0
        setScreen(
            replicas = 4,
            onConfirmClick = { confirmClicked = true },
            onDismissRequest = { dismissCount += 1 },
        )

        composeRule.onNodeWithText("Apply").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.runOnIdle {
            assertTrue(confirmClicked)
            assertEquals(1, dismissCount)
        }
    }

    private fun setScreen(
        replicas: Int,
        onDismissRequest: () -> Unit = {},
        onDecreaseClick: () -> Unit = {},
        onIncreaseClick: () -> Unit = {},
        onConfirmClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ScaleDialog(
                    replicas = replicas,
                    onDismissRequest = onDismissRequest,
                    onDecreaseClick = onDecreaseClick,
                    onIncreaseClick = onIncreaseClick,
                    onConfirmClick = onConfirmClick,
                )
            }
        }
    }
}
