package com.kubedroid.feature.rbac.domain.model

/**
 * Stub RBAC binding model for feature contract definition.
 */
data class RbacBinding(
    val name: String,
    val namespace: String?,
    val roleRefName: String,
    val subjectNames: List<String> = emptyList(),
)
