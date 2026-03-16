package com.kubedroid.core.database.cache

data class StaleDataIndicator(
    val isFresh: Boolean,
    val lastFetchedAt: Long?,
    val source: Source,
) {
    enum class Source {
        Live,
        Cache,
    }
}
