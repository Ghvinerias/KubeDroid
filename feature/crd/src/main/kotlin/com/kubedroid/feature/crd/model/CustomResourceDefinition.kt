package com.kubedroid.feature.crd.model

data class CustomResourceDefinition(
    val name: String,
    val group: String,
    val version: String,
    val scope: String,
    val kind: String,
)
