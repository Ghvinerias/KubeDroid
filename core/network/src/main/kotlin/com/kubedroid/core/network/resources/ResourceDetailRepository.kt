package com.kubedroid.core.network.resources

/**
 * Contract for fetching resource detail and applying YAML edits.
 */
interface ResourceDetailRepository {
    suspend fun getResourceDetail(
        kind: String,
        name: String,
        namespace: String? = null,
    ): Result<ResourceDetail>

    suspend fun applyYaml(
        kind: String,
        name: String,
        yaml: String,
        namespace: String? = null,
    ): Result<YamlEditResult>
}
