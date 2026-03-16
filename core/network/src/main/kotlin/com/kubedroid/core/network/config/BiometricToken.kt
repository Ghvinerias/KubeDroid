package com.kubedroid.core.network.config

/**
 * Time-bound proof that biometric authentication succeeded.
 */
data class BiometricToken(
    private val proof: String,
    private val expiresAtEpochMillis: Long,
) {
    internal fun isValid(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        return proof.isNotBlank() && nowEpochMillis <= expiresAtEpochMillis
    }

    companion object {
        fun fromAuthProof(
            proof: String,
            validForMillis: Long = DEFAULT_VALIDITY_MILLIS,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): BiometricToken {
            require(proof.isNotBlank()) { "Biometric proof must not be blank" }
            require(validForMillis > 0L) { "Biometric token validity must be greater than zero" }
            return BiometricToken(
                proof = proof,
                expiresAtEpochMillis = nowEpochMillis + validForMillis,
            )
        }

        private const val DEFAULT_VALIDITY_MILLIS = 30_000L
    }
}
