package com.kubedroid.core.network.config

/**
 * Core ConfigMap model used by list/watch/get/update operations.
 */
data class ConfigMap(
    val name: String,
    val namespace: String,
    val data: Map<String, String>,
    val age: String,
)
