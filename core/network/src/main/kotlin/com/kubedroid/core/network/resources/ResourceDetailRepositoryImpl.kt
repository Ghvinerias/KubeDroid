package com.kubedroid.core.network.resources

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.CoreV1Event
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Yaml
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ResourceDetailRepositoryImpl(
    private val resourceDetailClient: ResourceDetailClient,
    private val yamlApplyService: YamlApplyService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ResourceDetailRepository {

    constructor(
        apiClientProvider: () -> ApiClient,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        kindCatalog: ResourceKindCatalog = ResourceKindCatalog.Default,
    ) : this(
        resourceDetailClient = ResourceDetailClient.Default(apiClientProvider = apiClientProvider, kindCatalog = kindCatalog),
        yamlApplyService = DefaultYamlApplyService(
            yamlParser = ResourceYamlParser.Default,
            yamlApplyClient = YamlApplyClient.Default(apiClientProvider = apiClientProvider, kindCatalog = kindCatalog),
            ioDispatcher = ioDispatcher,
        ),
        ioDispatcher = ioDispatcher,
    )

    constructor(
        kubeConfigPath: Path,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        kindCatalog: ResourceKindCatalog = ResourceKindCatalog.Default,
    ) : this(
        apiClientProvider = { buildResourceApiClient(kubeConfigPath) },
        ioDispatcher = ioDispatcher,
        kindCatalog = kindCatalog,
    )

    override suspend fun getResourceDetail(
        kind: String,
        name: String,
        namespace: String?,
    ): Result<ResourceDetail> = withContext(ioDispatcher) {
        runCatching {
            resourceDetailClient.fetch(kind = kind, name = name, namespace = namespace)
        }.mapFailure(::mapRepositoryError)
    }

    override suspend fun applyYaml(
        kind: String,
        name: String,
        yaml: String,
        namespace: String?,
    ): Result<YamlEditResult> = withContext(ioDispatcher) {
        yamlApplyService
            .applyYaml(kind = kind, name = name, yaml = yaml, namespace = namespace)
            .mapFailure(::mapRepositoryError)
    }

    private fun mapRepositoryError(throwable: Throwable): Throwable {
        return when (throwable) {
            is InvalidYamlException -> throwable
            is ResourceForbiddenException -> throwable
            is ResourceNotFoundException -> throwable
            is YamlApplyForbiddenException -> throwable
            is YamlApplyException -> throwable
            is ResourceDetailApiException -> throwable
            is ApiException -> when (throwable.code) {
                403 -> ResourceForbiddenException(cause = throwable)
                404 -> ResourceDetailApiException("Resource was not found", throwable)
                else -> ResourceDetailApiException(
                    message = "Resource API returned HTTP ${throwable.code}",
                    cause = throwable,
                )
            }
            else -> ResourceDetailApiException(
                message = throwable.message ?: "Resource request failed",
                cause = throwable,
            )
        }
    }
}

interface ResourceDetailClient {
    fun fetch(
        kind: String,
        name: String,
        namespace: String? = null,
    ): ResourceDetail

    data class Default(
        private val apiClientProvider: () -> ApiClient,
        private val kindCatalog: ResourceKindCatalog = ResourceKindCatalog.Default,
    ) : ResourceDetailClient {
        override fun fetch(kind: String, name: String, namespace: String?): ResourceDetail {
            val normalizedKind = kind.trim().ifEmpty {
                throw ResourceDetailApiException("Resource kind cannot be blank")
            }
            val normalizedName = name.trim().ifEmpty {
                throw ResourceDetailApiException("Resource name cannot be blank")
            }
            val normalizedNamespace = namespace?.trim()?.takeIf { it.isNotEmpty() }

            val candidates = kindCatalog.resolveReadDescriptors(normalizedKind)
            var notFoundError: ApiException? = null

            for (candidate in candidates) {
                val api = DynamicKubernetesApi(
                    candidate.group,
                    candidate.version,
                    candidate.plural,
                    apiClientProvider(),
                )
                try {
                    val response = if (normalizedNamespace == null) {
                        api.get(normalizedName)
                    } else {
                        api.get(normalizedNamespace, normalizedName)
                    }
                    if (!response.isSuccess) {
                        response.throwsApiException()
                    }

                    val resource = response.getObject()
                        ?: throw ResourceDetailApiException("Empty response while fetching resource")
                    return ResourceDetail(
                        name = resource.metadata?.name ?: normalizedName,
                        namespace = resource.metadata?.namespace ?: normalizedNamespace,
                        kind = resource.kind ?: normalizedKind,
                        apiVersion = resource.apiVersion ?: candidate.asApiVersion(),
                        resourceVersion = resource.metadata?.resourceVersion,
                        yaml = Yaml.dump(resource),
                        events = fetchEvents(
                            apiClientProvider = apiClientProvider,
                            kind = resource.kind ?: normalizedKind,
                            name = resource.metadata?.name ?: normalizedName,
                            namespace = resource.metadata?.namespace ?: normalizedNamespace,
                        ),
                    )
                } catch (apiException: ApiException) {
                    when (apiException.code) {
                        404 -> notFoundError = apiException
                        403 -> throw ResourceForbiddenException(cause = apiException)
                        else -> throw ResourceDetailApiException(
                            message = "Resource API returned HTTP ${apiException.code}",
                            cause = apiException,
                        )
                    }
                }
            }

            throw ResourceNotFoundException(
                kind = normalizedKind,
                name = normalizedName,
                namespace = normalizedNamespace,
                cause = notFoundError,
            )
        }

        private fun fetchEvents(
            apiClientProvider: () -> ApiClient,
            kind: String,
            name: String,
            namespace: String?,
        ): List<KubeEvent> {
            val selector = "involvedObject.kind=$kind,involvedObject.name=$name"
            return runCatching {
                val api = CoreV1Api(apiClientProvider())
                val items = if (namespace.isNullOrBlank()) {
                    api.listEventForAllNamespaces().fieldSelector(selector).execute().items
                } else {
                    api.listNamespacedEvent(namespace).fieldSelector(selector).execute().items
                }.orEmpty()

                items.mapNotNull { event -> event.toDomainEvent() }
            }.getOrElse {
                emptyList()
            }
        }

        private fun CoreV1Event.toDomainEvent(): KubeEvent? {
            val messageText = message ?: return null
            return KubeEvent(
                reason = reason,
                type = type,
                message = messageText,
                source = source?.component ?: reportingComponent,
                count = count,
                firstTimestampEpochMillis = firstTimestamp?.toInstant()?.toEpochMilli()
                    ?: eventTime?.toInstant()?.toEpochMilli(),
                lastTimestampEpochMillis = lastTimestamp?.toInstant()?.toEpochMilli()
                    ?: eventTime?.toInstant()?.toEpochMilli(),
            )
        }
    }
}

interface ResourceKindCatalog {
    fun resolveReadDescriptors(kind: String): List<ResourceDescriptor>
    fun resolveWriteDescriptor(kind: String, apiVersion: String): ResourceDescriptor

    data object Default : ResourceKindCatalog {
        private val apiVersionsByKind = mapOf(
            "pod" to listOf("v1"),
            "service" to listOf("v1"),
            "configmap" to listOf("v1"),
            "secret" to listOf("v1"),
            "namespace" to listOf("v1"),
            "node" to listOf("v1"),
            "deployment" to listOf("apps/v1"),
            "daemonset" to listOf("apps/v1"),
            "statefulset" to listOf("apps/v1"),
            "replicaset" to listOf("apps/v1"),
            "job" to listOf("batch/v1"),
            "cronjob" to listOf("batch/v1"),
            "ingress" to listOf("networking.k8s.io/v1"),
        )

        override fun resolveReadDescriptors(kind: String): List<ResourceDescriptor> {
            val normalizedKind = kind.trim()
            val kindKey = normalizedKind.lowercase()
            val apiVersions = apiVersionsByKind[kindKey]
                ?: listOf("v1", "apps/v1", "batch/v1", "networking.k8s.io/v1")
            val plural = inferPlural(normalizedKind)
            return apiVersions.map { apiVersion ->
                val (group, version) = splitApiVersion(apiVersion)
                ResourceDescriptor(group = group, version = version, plural = plural)
            }
        }

        override fun resolveWriteDescriptor(kind: String, apiVersion: String): ResourceDescriptor {
            val (group, version) = splitApiVersion(apiVersion)
            return ResourceDescriptor(
                group = group,
                version = version,
                plural = inferPlural(kind),
            )
        }

        private fun inferPlural(kind: String): String {
            val normalized = kind.trim().lowercase()
            return when {
                normalized.endsWith("s") -> "${normalized}es"
                normalized.endsWith("y") -> "${normalized.dropLast(1)}ies"
                else -> "${normalized}s"
            }
        }

        private fun splitApiVersion(apiVersion: String): Pair<String, String> {
            val value = apiVersion.trim()
            return if (value.contains('/')) {
                val parts = value.split('/', limit = 2)
                parts[0] to parts[1]
            } else {
                "" to value
            }
        }
    }
}

data class ResourceDescriptor(
    val group: String,
    val version: String,
    val plural: String,
) {
    fun asApiVersion(): String = if (group.isBlank()) version else "$group/$version"
}

private fun buildResourceApiClient(kubeConfigPath: Path): ApiClient {
    if (!Files.exists(kubeConfigPath)) {
        throw ResourceDetailApiException("Kubeconfig file not found at $kubeConfigPath")
    }
    val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    val kubeConfig = try {
        KubeConfig.loadKubeConfig(StringReader(raw))
    } catch (throwable: Throwable) {
        throw ResourceDetailApiException("Invalid kubeconfig content", throwable)
    }
    return ClientBuilder.kubeconfig(kubeConfig)
        .build()
        .setLenientOnJson(true)
}

private fun <T> Result<T>.mapFailure(mapper: (Throwable) -> Throwable): Result<T> {
    return fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapper(it)) },
    )
}
