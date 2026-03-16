package com.kubedroid.core.network.resources

/**
 * Semantic outcomes of a YAML edit operation.
 * Transport/runtime failures should be returned via [Result.failure].
 */
sealed class YamlEditResult {
    data class Applied(val resourceVersion: String?) : YamlEditResult()
    data object NoChanges : YamlEditResult()
    data class Conflict(val currentResourceVersion: String?) : YamlEditResult()
}
