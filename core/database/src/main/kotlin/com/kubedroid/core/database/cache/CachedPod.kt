package com.kubedroid.core.database.cache

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "cached_pods",
    primaryKeys = ["contextName", "namespace", "name"],
    indices = [
        Index(value = ["contextName", "namespace"]),
        Index(value = ["contextName", "lastFetchedAt"]),
    ],
)
data class CachedPod(
    val contextName: String,
    val namespace: String,
    val name: String,
    val status: String,
    val lastFetchedAt: Long,
)
