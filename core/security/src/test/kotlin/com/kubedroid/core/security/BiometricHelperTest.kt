package com.kubedroid.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.ShadowAndroidxBiometricManager
import androidx.biometric.ShadowBiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowAndroidxBiometricManager::class, ShadowBiometricPrompt::class])
class BiometricHelperTest {
    private val promptConfig = BiometricHelper.PromptConfig(
        title = "Unlock secret",
        cancelText = "Cancel",
    )

    @After
    fun tearDown() {
        ShadowAndroidxBiometricManager.reset()
        ShadowBiometricPrompt.reset()
    }

    @Test
    fun test_authenticate_biometricUnavailable_returnsUnavailable() = runTest {
        val helper = BiometricHelper()
        val activity = createActivity()
        ShadowAndroidxBiometricManager.canAuthenticateResult = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE

        val result = helper.authenticate(activity, promptConfig)

        assertEquals(
            BiometricHelper.AuthResult.Unavailable(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
            result,
        )
    }

    @Test
    fun test_authenticate_callbackSuccess_mapsToSuccessResult() = runTest {
        shadowResult(ShadowBiometricPrompt.Result.Success)

        val result = BiometricHelper().authenticate(createActivity(), promptConfig)

        assertEquals(BiometricHelper.AuthResult.Success, result)
    }

    @Test
    fun test_authenticate_callbackFailed_mapsToFailedResult() = runTest {
        shadowResult(ShadowBiometricPrompt.Result.Failed)

        val result = BiometricHelper().authenticate(createActivity(), promptConfig)

        assertEquals(BiometricHelper.AuthResult.Failed, result)
    }

    @Test
    fun test_authenticate_callbackError_mapsToErrorResult() = runTest {
        shadowResult(ShadowBiometricPrompt.Result.Error(11, "temporarily locked"))

        val result = BiometricHelper().authenticate(createActivity(), promptConfig)

        assertEquals(BiometricHelper.AuthResult.Error(11, "temporarily locked"), result)
    }

    private fun createActivity(): FragmentActivity {
        return Robolectric.buildActivity(FragmentActivity::class.java)
            .setup()
            .get()
    }

    private fun shadowResult(result: ShadowBiometricPrompt.Result) {
        ShadowBiometricPrompt.nextResult = result
    }
}
