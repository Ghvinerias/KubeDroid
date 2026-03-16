package com.kubedroid.core.network.resources

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.util.Yaml
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface YamlApplyService {
    suspend fun applyYaml(
        kind: String,
        name: String,
        yaml: String,
        namespace: String? = null,
    ): Result<YamlEditResult>
}

class DefaultYamlApplyService(
    private val yamlParser: ResourceYamlParser,
    private val yamlApplyClient: YamlApplyClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : YamlApplyService {

    override suspend fun applyYaml(
        kind: String,
        name: String,
        yaml: String,
        namespace: String?,
    ): Result<YamlEditResult> = withContext(ioDispatcher) {
        val normalizedKind = kind.trim()
        val normalizedName = name.trim()
        val normalizedNamespace = namespace?.trim()?.takeIf { it.isNotEmpty() }

        if (normalizedKind.isEmpty()) {
            return@withContext Result.failure(InvalidYamlException("YAML kind cannot be blank"))
        }
        if (normalizedName.isEmpty()) {
            return@withContext Result.failure(InvalidYamlException("YAML metadata.name cannot be blank"))
        }

        val parsed = try {
            yamlParser.parse(yaml)
        } catch (throwable: Throwable) {
            return@withContext Result.failure(
                when (throwable) {
                    is InvalidYamlException -> throwable
                    else -> InvalidYamlException("Invalid YAML content", throwable)
                },
            )
        }

        try {
            parsed.validateAgainstRequest(
                expectedKind = normalizedKind,
                expectedName = normalizedName,
                expectedNamespace = normalizedNamespace,
            )
        } catch (throwable: Throwable) {
            return@withContext Result.failure(
                when (throwable) {
                    is InvalidYamlException -> throwable
                    else -> InvalidYamlException("Invalid YAML content", throwable)
                },
            )
        }

        return@withContext runCatching {
            val response = yamlApplyClient.apply(parsed)
            if (response.changed) {
                YamlEditResult.Applied(resourceVersion = response.resourceVersion)
            } else {
                YamlEditResult.NoChanges
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { throwable ->
                when (throwable) {
                    is ApiException -> when (throwable.code) {
                        403 -> Result.failure(YamlApplyForbiddenException(cause = throwable))
                        409 -> Result.success(
                            YamlEditResult.Conflict(
                                currentResourceVersion = runCatching {
                                    yamlApplyClient.fetchCurrentResourceVersion(parsed)
                                }.getOrNull(),
                            ),
                        )
                        else -> Result.failure(
                            YamlApplyException(
                                message = "YAML apply failed with HTTP ${throwable.code}",
                                cause = throwable,
                            ),
                        )
                    }
                    is InvalidYamlException -> Result.failure(throwable)
                    else -> Result.failure(
                        YamlApplyException(
                            message = throwable.message ?: "YAML apply failed",
                            cause = throwable,
                        ),
                    )
                }
            },
        )
    }
}

fun interface ResourceYamlParser {
    fun parse(yaml: String): ParsedYamlResource

    data object Default : ResourceYamlParser {
        private val gson = Gson()

        override fun parse(yaml: String): ParsedYamlResource {
            val raw = try {
                Yaml.load(yaml)
            } catch (throwable: Throwable) {
                throw InvalidYamlException("Invalid YAML content", throwable)
            }

            val jsonObject = try {
                gson.toJsonTree(raw).asJsonObject
            } catch (throwable: Throwable) {
                throw InvalidYamlException("YAML content must describe a Kubernetes object", throwable)
            }

            val dynamicObject = try {
                DynamicKubernetesObject(jsonObject)
            } catch (throwable: Throwable) {
                throw InvalidYamlException("YAML content is not a valid Kubernetes resource", throwable)
            }

            val apiVersion = dynamicObject.apiVersion?.trim().orEmpty()
            val kind = dynamicObject.kind?.trim().orEmpty()
            val metadata = dynamicObject.metadata
            val name = metadata?.name?.trim().orEmpty()
            if (apiVersion.isEmpty() || kind.isEmpty() || name.isEmpty()) {
                throw InvalidYamlException(
                    "YAML must include apiVersion, kind, and metadata.name",
                )
            }

            return ParsedYamlResource(
                raw = jsonObject,
                dynamicObject = dynamicObject,
                apiVersion = apiVersion,
                kind = kind,
                name = name,
                namespace = metadata.namespace?.trim()?.takeIf { it.isNotEmpty() },
                resourceVersion = metadata.resourceVersion?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

interface YamlApplyClient {
    @Throws(ApiException::class)
    fun apply(resource: ParsedYamlResource): YamlApplyResponse

    @Throws(ApiException::class)
    fun fetchCurrentResourceVersion(resource: ParsedYamlResource): String?

    data class Default(
        private val apiClientProvider: () -> ApiClient,
        private val kindCatalog: ResourceKindCatalog = ResourceKindCatalog.Default,
    ) : YamlApplyClient {
        override fun apply(resource: ParsedYamlResource): YamlApplyResponse {
            val descriptor = kindCatalog.resolveWriteDescriptor(resource.kind, resource.apiVersion)
            val api = DynamicKubernetesApi(
                descriptor.group,
                descriptor.version,
                descriptor.plural,
                apiClientProvider(),
            )
            val response = api.update(resource.dynamicObject)
            if (!response.isSuccess) {
                response.throwsApiException()
            }
            val currentVersion = response.getObject()?.metadata?.resourceVersion
            val changed = resource.resourceVersion == null || resource.resourceVersion != currentVersion
            return YamlApplyResponse(resourceVersion = currentVersion, changed = changed)
        }

        override fun fetchCurrentResourceVersion(resource: ParsedYamlResource): String? {
            val descriptor = kindCatalog.resolveWriteDescriptor(resource.kind, resource.apiVersion)
            val api = DynamicKubernetesApi(
                descriptor.group,
                descriptor.version,
                descriptor.plural,
                apiClientProvider(),
            )
            val response = if (resource.namespace.isNullOrBlank()) {
                api.get(resource.name)
            } else {
                api.get(resource.namespace, resource.name)
            }
            if (!response.isSuccess) {
                response.throwsApiException()
            }
            return response.getObject()?.metadata?.resourceVersion
        }
    }
}

data class ParsedYamlResource(
    val raw: JsonObject,
    val dynamicObject: DynamicKubernetesObject,
    val apiVersion: String,
    val kind: String,
    val name: String,
    val namespace: String?,
    val resourceVersion: String?,
) {
    fun validateAgainstRequest(
        expectedKind: String,
        expectedName: String,
        expectedNamespace: String?,
    ) {
        if (!kind.equals(expectedKind, ignoreCase = true)) {
            throw InvalidYamlException("YAML kind '$kind' does not match '$expectedKind'")
        }
        if (name != expectedName) {
            throw InvalidYamlException("YAML metadata.name '$name' does not match '$expectedName'")
        }
        if (expectedNamespace != null && namespace != null && expectedNamespace != namespace) {
            throw InvalidYamlException(
                "YAML metadata.namespace '$namespace' does not match '$expectedNamespace'",
            )
        }
        if (expectedNamespace != null && dynamicObject.metadata?.namespace.isNullOrBlank()) {
            val metadata = dynamicObject.metadata ?: io.kubernetes.client.openapi.models.V1ObjectMeta()
            metadata.namespace = expectedNamespace
            dynamicObject.metadata = metadata
        }
    }
}

data class YamlApplyResponse(
    val resourceVersion: String?,
    val changed: Boolean,
)
