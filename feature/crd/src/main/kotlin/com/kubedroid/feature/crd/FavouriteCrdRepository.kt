package com.kubedroid.feature.crd

import com.kubedroid.feature.crd.model.CustomResourceDefinition
import kotlinx.coroutines.flow.Flow

interface FavouriteCrdRepository {
    fun observeFavouriteCrds(): Flow<List<CustomResourceDefinition>>

    suspend fun setFavourite(
        crd: CustomResourceDefinition,
        favourite: Boolean,
    )

    suspend fun isFavourite(crd: CustomResourceDefinition): Boolean
}
