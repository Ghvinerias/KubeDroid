package com.kubedroid.core.database.cache

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedPod::class,
        CachedDeployment::class,
        CachedNode::class,
        CachedNamespace::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}
