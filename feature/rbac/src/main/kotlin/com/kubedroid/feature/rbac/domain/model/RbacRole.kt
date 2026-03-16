package com.kubedroid.feature.rbac.domain.model

/**
 * Stub RBAC role model for feature contract definition.
 */
data class RbacRole(
    val name: String,
    val namespace: String?,
    val rules: List<String> = emptyList(),
)
