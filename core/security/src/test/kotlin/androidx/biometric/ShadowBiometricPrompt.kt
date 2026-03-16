package androidx.biometric

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(BiometricPrompt::class)
class ShadowBiometricPrompt {
    private lateinit var callback: BiometricPrompt.AuthenticationCallback
    private var executor: Executor? = null

    @Implementation
    protected fun __constructor__(
        activity: FragmentActivity,
        executor: Executor,
        callback: BiometricPrompt.AuthenticationCallback,
    ) {
        this.executor = executor
        this.callback = callback
    }

    @Implementation
    protected fun __constructor__(
        fragment: Fragment,
        executor: Executor,
        callback: BiometricPrompt.AuthenticationCallback,
    ) {
        this.executor = executor
        this.callback = callback
    }

    @Implementation
    protected fun authenticate(promptInfo: BiometricPrompt.PromptInfo) {
        when (val result = nextResult) {
            is Result.Success -> {
                callback.onAuthenticationSucceeded(
                    BiometricPrompt.AuthenticationResult(
                        null,
                        BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC,
                    ),
                )
            }
            is Result.Failed -> callback.onAuthenticationFailed()
            is Result.Error -> callback.onAuthenticationError(result.code, result.message)
        }
    }

    @Implementation
    protected fun cancelAuthentication() {
        // No-op for tests.
    }

    companion object {
        var nextResult: Result = Result.Success

        fun reset() {
            nextResult = Result.Success
        }
    }

    sealed interface Result {
        data object Success : Result
        data object Failed : Result
        data class Error(val code: Int, val message: CharSequence) : Result
    }
}
