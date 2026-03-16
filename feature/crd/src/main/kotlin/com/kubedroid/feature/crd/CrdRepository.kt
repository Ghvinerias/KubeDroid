package com.kubedroid.feature.crd

import com.kubedroid.core.network.resources.YamlEditResult
import com.kubedroid.feature.crd.model.CustomResource
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import kotlinx.coroutines.flow.Flow

interface CrdRepository {

    suspend fun listCrds(): Result<List<CustomResourceDefinition>>

    suspend fun listCustomResources(
        crd: CustomResourceDefinition,
        namespace: String,
    ): Result<List<CustomResource>>

    suspend fun getCustomResource(
        crd: CustomResourceDefinition,
        name: String,
        namespace: String,
    ): Result<CustomResource>

    suspend fun applyCustomResourceYaml(
        crd: CustomResourceDefinition,
        name: String,
        namespace: String,
        yaml: String,
    ): Result<YamlEditResult>

    fun watchCustomResources(
        crd: CustomResourceDefinition,
        namespace: String,
    ): Flow<Result<List<CustomResource>>>
}
