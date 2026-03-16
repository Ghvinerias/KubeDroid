package com.kubedroid.feature.rbac.domain.repository

import com.kubedroid.feature.rbac.domain.model.CanIResult
import com.kubedroid.feature.rbac.domain.model.RbacBinding
import com.kubedroid.feature.rbac.domain.model.RbacRole

/**
 * Stub RBAC repository contract.
 */
interface RbacRepository {
    suspend fun listRoles(namespace: String): List<RbacRole>

    suspend fun listClusterRoles(): List<RbacRole>

    suspend fun listRoleBindings(namespace: String): List<RbacBinding>

    suspend fun listClusterRoleBindings(): List<RbacBinding>

    suspend fun canI(
        verb: String,
        resource: String,
        namespace: String?,
        subjectName: String,
    ): CanIResult
}
