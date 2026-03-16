package com.kubedroid.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetConfigActivityTest {

    @Test
    fun extractAppWidgetId_returnsInvalidWhenIntentMissingId() {
        val intent = Intent()
        val appWidgetId = extractAppWidgetId(intent)
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, appWidgetId)
    }

    @Test
    fun extractAppWidgetId_returnsPassedAppWidgetId() {
        val intent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 42)
        val appWidgetId = extractAppWidgetId(intent)
        assertEquals(42, appWidgetId)
    }

    @Test
    fun toInitialSettings_usesDefaultsWhenConfigMissing() {
        val state = (null as NotificationConfig?).toInitialSettings()
        assertTrue(state.crashLoopEnabled)
        assertTrue(state.nodeNotReadyEnabled)
        assertEquals(1, state.warningThreshold)
        assertEquals(15, state.refreshIntervalMinutes)
    }
}
