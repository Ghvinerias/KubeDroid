package com.kubedroid.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.ShadowAndroidxBiometricManager
import androidx.biometric.ShadowBiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowAndroidxBiometricManager::class, ShadowBiometricPrompt::class])
class SecretRevealManagerTest {
    private val promptConfig = BiometricHelper.PromptConfig(
        title = "Reveal",
        cancelText = "Cancel",
    )

    @After
    fun tearDown() {
        ShadowAndroidxBiometricManager.reset()
        ShadowBiometricPrompt.reset()
    }

    @Test
    fun test_reveal_success_setsSecretAndReturnsTrue() = runTest {
        ShadowAndroidxBiometricManager.canAuthenticateResult = BiometricManager.BIOMETRIC_SUCCESS
        ShadowBiometricPrompt.nextResult = ShadowBiometricPrompt.Result.Success
        val manager = SecretRevealManager(
            biometricHelper = BiometricHelper(),
            scope = this,
            autoHideMillis = 5_000L,
        )

        val revealed = manager.reveal(createActivity(), promptConfig) { "super-secret" }

        assertTrue(revealed)
        assertEquals("super-secret", manager.revealedSecret.value)
    }

    @Test
    fun test_reveal_authFailure_doesNotExposeSecret() = runTest {
        ShadowAndroidxBiometricManager.canAuthenticateResult = BiometricManager.BIOMETRIC_SUCCESS
        ShadowBiometricPrompt.nextResult = ShadowBiometricPrompt.Result.Failed
        val manager = SecretRevealManager(
            biometricHelper = BiometricHelper(),
            scope = this,
            autoHideMillis = 5_000L,
        )
        var secretProviderCalled = false

        val revealed = manager.reveal(createActivity(), promptConfig) {
            secretProviderCalled = true
            "should-not-be-revealed"
        }

        assertFalse(revealed)
        assertFalse(secretProviderCalled)
        assertNull(manager.revealedSecret.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun test_reveal_autoHideClearsSecretAfterTimeout() = runTest {
        ShadowAndroidxBiometricManager.canAuthenticateResult = BiometricManager.BIOMETRIC_SUCCESS
        ShadowBiometricPrompt.nextResult = ShadowBiometricPrompt.Result.Success
        val manager = SecretRevealManager(
            biometricHelper = BiometricHelper(),
            scope = this,
            autoHideMillis = 1_000L,
        )

        manager.reveal(createActivity(), promptConfig) { "ephemeral" }
        assertEquals("ephemeral", manager.revealedSecret.value)

        advanceTimeBy(999L)
        runCurrent()
        assertEquals("ephemeral", manager.revealedSecret.value)

        advanceTimeBy(1L)
        runCurrent()
        assertNull(manager.revealedSecret.value)
    }

    private fun createActivity(): FragmentActivity {
        return Robolectric.buildActivity(FragmentActivity::class.java)
            .setup()
            .get()
    }
}
