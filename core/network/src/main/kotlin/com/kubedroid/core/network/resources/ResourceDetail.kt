package com.kubedroid.core.network.resources

/**
 * Generic resource detail model for detail screens and YAML editing.
 */
data class ResourceDetail(
    val name: String,
    val namespace: String?,
    val kind: String,
    val apiVersion: String,
    val resourceVersion: String?,
    val yaml: String,
    val events: List<KubeEvent> = emptyList(),
)
