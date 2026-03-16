package com.kubedroid.feature.crd.local

import androidx.room.Entity

@Entity(
    tableName = "favourite_crds",
    primaryKeys = ["group", "version", "kind"],
)
data class FavouriteCrd(
    val name: String,
    val group: String,
    val version: String,
    val scope: String,
    val kind: String,
)
