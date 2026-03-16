package com.kubedroid.core.network.config

/**
 * Core Secret metadata model used by list/watch/get operations.
 *
 * Secret values are intentionally excluded to avoid storing sensitive material in memory.
 */
data class Secret(
    val name: String,
    val namespace: String,
    val type: String,
    val dataKeys: List<String>,
)
