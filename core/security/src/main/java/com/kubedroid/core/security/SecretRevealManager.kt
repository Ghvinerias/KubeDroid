/*
 * Threat model:
 * - Gates sensitive secret reveal behind Class 3 biometric authentication.
 * - Minimizes shoulder-surfing risk by auto-hiding revealed secrets after a short timeout.
 * - Keeps no long-lived background jobs beyond a caller-provided coroutine scope.
 */
package com.kubedroid.core.security

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SecretRevealManager(
    private val biometricHelper: BiometricHelper,
    private val scope: CoroutineScope,
    private val autoHideMillis: Long = DEFAULT_AUTO_HIDE_MILLIS,
) {
    private val _revealedSecret = MutableStateFlow<String?>(null)
    val revealedSecret: StateFlow<String?> = _revealedSecret.asStateFlow()

    private var autoHideJob: Job? = null

    // Threat model: reveal only after explicit biometric verification and limit exposure window.
    suspend fun reveal(
        activity: FragmentActivity,
        promptConfig: BiometricHelper.PromptConfig,
        secretProvider: suspend () -> String,
    ): Boolean {
        val authResult = biometricHelper.authenticate(activity, promptConfig)
        if (authResult !is BiometricHelper.AuthResult.Success) {
            return false
        }

        val secret = secretProvider()
        _revealedSecret.value = secret
        scheduleAutoHide()
        return true
    }

    // Threat model: allows immediate concealment when app backgrounding or user leaves sensitive screen.
    fun hideNow() {
        autoHideJob?.cancel()
        autoHideJob = null
        _revealedSecret.value = null
    }

    // Threat model: auto-clear secret after fixed timeout to reduce shoulder-surfing and screenshot risk.
    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(autoHideMillis)
            _revealedSecret.value = null
        }
    }

    private companion object {
        const val DEFAULT_AUTO_HIDE_MILLIS = 30_000L
    }
}
