package com.kubedroid.feature.rbac.domain.model

/**
 * Stub authorization decision used by RBAC checks.
 */
sealed class CanIResult {
    data object Allowed : CanIResult()
    data object Denied : CanIResult()
    data object Unknown : CanIResult()
}
