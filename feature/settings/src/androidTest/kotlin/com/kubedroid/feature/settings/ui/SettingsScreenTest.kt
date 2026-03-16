package com.kubedroid.feature.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.kubedroid.feature.settings.R
import com.kubedroid.feature.settings.domain.model.AppPreferences
import com.kubedroid.feature.settings.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sectionsRender_withExpectedHeadersAndRows() {
        setScreen()

        composeRule.onNodeWithText(string(R.string.settings_appearance_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_behaviour_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_security_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_data_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_about_title)).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.settings_default_namespace_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_clear_cache_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_version_title)).assertIsDisplayed()
    }

    @Test
    fun themeSelection_invokesCallbackAndUpdatesObservedThemeState() {
        var selectedTheme: AppTheme? = null

        composeRule.setContent {
            var state by mutableStateOf(
                SettingsUiState(
                    preferences = AppPreferences(theme = AppTheme.System),
                    cacheSizeBytes = 0L,
                    appVersion = "1.0.0",
                    appBuildNumber = "1",
                ),
            )
            MaterialTheme {
                SettingsScreen(
                    state = state,
                    onThemeSelected = { theme ->
                        selectedTheme = theme
                        state = state.copy(preferences = state.preferences.copy(theme = theme))
                    },
                    onDefaultNamespaceChanged = {},
                    onLogBufferSizeSelected = {},
                    onMetricsIntervalSelected = {},
                    onBiometricLockChanged = {},
                    onClearCache = {},
                    onExportLogsClick = {},
                    onOpenSourceLicensesClick = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.settings_theme_dark)).performClick()
        composeRule.runOnIdle { assertEquals(AppTheme.Dark, selectedTheme) }
    }

    @Test
    fun clearCache_clickShowsConfirmation_andConfirmInvokesCallback() {
        var clearCacheCalls = 0
        setScreen(
            onClearCache = { clearCacheCalls += 1 },
        )

        composeRule.onNodeWithText(string(R.string.settings_clear_cache_action)).performClick()
        composeRule.onNodeWithText(string(R.string.settings_clear_cache_confirm_title)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, clearCacheCalls) }

        composeRule.onNodeWithText(string(R.string.settings_clear_cache_confirm_action)).performClick()
        composeRule.runOnIdle { assertEquals(1, clearCacheCalls) }
    }

    private fun setScreen(
        onClearCache: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        cacheSizeBytes = 2048L,
                        appVersion = "1.0.0",
                        appBuildNumber = "1",
                    ),
                    onThemeSelected = {},
                    onDefaultNamespaceChanged = {},
                    onLogBufferSizeSelected = {},
                    onMetricsIntervalSelected = {},
                    onBiometricLockChanged = {},
                    onClearCache = onClearCache,
                    onExportLogsClick = {},
                    onOpenSourceLicensesClick = {},
                )
            }
        }
    }

    private fun string(id: Int): String {
        return InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    }
}
