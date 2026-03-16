package com.kubedroid.feature.helm.domain.model

data class HelmRelease(
    val name: String,
    val namespace: String,
    val chart: String,
    val chartVersion: String,
    val appVersion: String,
    val status: String,
    val lastDeployed: String,
)
