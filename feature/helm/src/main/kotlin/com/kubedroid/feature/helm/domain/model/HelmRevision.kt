package com.kubedroid.feature.helm.domain.model

data class HelmRevision(
    val revision: Int,
    val chart: String,
    val status: String,
    val updatedAt: String,
    val description: String,
)
