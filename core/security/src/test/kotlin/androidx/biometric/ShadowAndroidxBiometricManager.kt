package androidx.biometric

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(BiometricManager::class)
class ShadowAndroidxBiometricManager {
    @Implementation
    protected fun canAuthenticate(): Int {
        return canAuthenticateResult
    }

    @Implementation
    protected fun canAuthenticate(authenticators: Int): Int {
        return canAuthenticateResult
    }

    companion object {
        var canAuthenticateResult: Int = BiometricManager.BIOMETRIC_SUCCESS

        fun reset() {
            canAuthenticateResult = BiometricManager.BIOMETRIC_SUCCESS
        }
    }
}
