package com.kubedroid.core.database.cache

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "cached_nodes",
    primaryKeys = ["contextName", "name"],
    indices = [Index(value = ["contextName", "lastFetchedAt"])],
)
data class CachedNode(
    val contextName: String,
    val name: String,
    val status: String,
    val lastFetchedAt: Long,
)
