package com.kubedroid.core.network.resources

class InvalidYamlException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class ResourceForbiddenException(
    message: String = "Forbidden to access resource",
    cause: Throwable? = null,
) : Exception(message, cause)

class ResourceNotFoundException(
    kind: String,
    name: String,
    namespace: String?,
    cause: Throwable? = null,
) : Exception(
    if (namespace.isNullOrBlank()) {
        "$kind '$name' was not found"
    } else {
        "$kind '$name' was not found in namespace '$namespace'"
    },
    cause,
)

class ResourceDetailApiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class YamlApplyForbiddenException(
    message: String = "Forbidden to apply YAML",
    cause: Throwable? = null,
) : Exception(message, cause)

class YamlApplyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
