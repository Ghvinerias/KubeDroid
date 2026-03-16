package com.kubedroid.core.network.storage

/**
 * Core PersistentVolume model used by list operations.
 */
data class PersistentVolume(
    val name: String,
    val capacity: String?,
    val accessModes: List<String>,
    val reclaimPolicy: String?,
    val status: String?,
    val storageClass: String?,
    val claimRef: String?,
)

/**
 * Core PersistentVolumeClaim model used by list/watch operations.
 */
data class PersistentVolumeClaim(
    val name: String,
    val namespace: String,
    val status: String?,
    val capacity: String?,
    val accessModes: List<String>,
    val storageClass: String?,
    val volumeName: String?,
)

/**
 * Core StorageClass model used by list operations.
 */
data class StorageClass(
    val name: String,
    val provisioner: String?,
    val reclaimPolicy: String?,
    val volumeBindingMode: String?,
    val allowVolumeExpansion: Boolean?,
)
