package com.kubedroid.core.database.cache

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "cached_deployments",
    primaryKeys = ["contextName", "namespace", "name"],
    indices = [
        Index(value = ["contextName", "namespace"]),
        Index(value = ["contextName", "lastFetchedAt"]),
    ],
)
data class CachedDeployment(
    val contextName: String,
    val namespace: String,
    val name: String,
    val desiredReplicas: Int,
    val availableReplicas: Int,
    val lastFetchedAt: Long,
)
