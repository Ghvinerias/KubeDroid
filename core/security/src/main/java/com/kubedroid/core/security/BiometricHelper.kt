/*
 * Threat model:
 * - Prevents unauthorized local users from accessing protected actions via weak authenticators.
 * - Enforces Class 3 biometrics only (BIOMETRIC_STRONG), excluding device credential fallback.
 * - Returns structured failure reasons so callers can block sensitive flows safely.
 */
package com.kubedroid.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BiometricHelper {
    // Threat model: blocks secret actions unless Class 3 biometric auth succeeds in the foreground.
    suspend fun authenticate(
        activity: FragmentActivity,
        promptConfig: PromptConfig,
    ): AuthResult {
        val biometricManager = BiometricManager.from(activity)
        val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            return AuthResult.Unavailable(canAuthenticate)
        }

        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) {
                        continuation.resume(AuthResult.Error(errorCode, errString.toString()))
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) {
                        continuation.resume(AuthResult.Success)
                    }
                }

                override fun onAuthenticationFailed() {
                    if (continuation.isActive) {
                        continuation.resume(AuthResult.Failed)
                    }
                }
            }

            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(promptConfig.title)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(promptConfig.cancelText)
                .apply {
                    promptConfig.subtitle?.let { setSubtitle(it) }
                    promptConfig.description?.let { setDescription(it) }
                }
                .build()

            continuation.invokeOnCancellation {
                biometricPrompt.cancelAuthentication()
            }
            biometricPrompt.authenticate(promptInfo)
        }
    }

    data class PromptConfig(
        val title: CharSequence,
        val subtitle: CharSequence? = null,
        val description: CharSequence? = null,
        val cancelText: CharSequence,
    )

    sealed interface AuthResult {
        data object Success : AuthResult
        data object Failed : AuthResult
        data class Error(val code: Int, val message: String) : AuthResult
        data class Unavailable(val reasonCode: Int) : AuthResult
    }
}
