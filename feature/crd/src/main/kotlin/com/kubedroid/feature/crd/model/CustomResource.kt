package com.kubedroid.feature.crd.model

data class CustomResource(
    val name: String,
    val namespace: String,
    val rawJson: String,
)
