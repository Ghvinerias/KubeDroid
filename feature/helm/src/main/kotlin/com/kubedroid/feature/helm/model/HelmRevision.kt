package com.kubedroid.feature.helm.model

data class HelmRevision(
    val revision: Int,
    val chart: String,
    val status: String,
    val updatedAt: Long?,
    val description: String,
)
