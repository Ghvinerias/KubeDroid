package com.kubedroid.feature.crd.impl

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kubedroid.core.network.resources.YamlEditResult
import com.kubedroid.feature.crd.CrdRepository
import com.kubedroid.feature.crd.FavouriteCrdRepository
import com.kubedroid.feature.crd.local.CrdDatabase
import com.kubedroid.feature.crd.local.FavouriteCrd
import com.kubedroid.feature.crd.local.FavouriteCrdDao
import com.kubedroid.feature.crd.model.CustomResource
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.ApiextensionsV1Api
import io.kubernetes.client.openapi.apis.CustomObjectsApi
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1CustomResourceDefinition
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Watch
import io.kubernetes.client.util.Yaml
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

@Singleton
class CrdRepositoryImpl : CrdRepository, FavouriteCrdRepository {
    private val apiClientProvider: () -> ApiClient
    private val favouriteDao: FavouriteCrdDao
    private val ioDispatcher: CoroutineDispatcher

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        apiClientProvider = { buildApiClient(FilePaths.kubeConfigPath(context)) },
        favouriteDao = CrdDatabase.create(context).favouriteCrdDao(),
        ioDispatcher = Dispatchers.IO,
    )

    internal constructor(
        apiClientProvider: () -> ApiClient,
        favouriteDao: FavouriteCrdDao,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        this.apiClientProvider = apiClientProvider
        this.favouriteDao = favouriteDao
        this.ioDispatcher = ioDispatcher
    }

    override suspend fun listCrds(): Result<List<CustomResourceDefinition>> = withContext(ioDispatcher) {
        runCatching {
            val api = ApiextensionsV1Api(apiClientProvider())
            val items = api.listCustomResourceDefinition().execute().items.orEmpty()
            val crds = items
                .mapNotNull { it.toDomainOrNull() }
                .sortedBy { it.name }

            val activeKeys = crds.map { it.favouriteKey() }.toSet()
            favouriteDao.listAll()
                .filter { it.favouriteKey() !in activeKeys }
                .forEach { stale ->
                    favouriteDao.delete(stale.group, stale.version, stale.kind)
                }

            crds
        }.mapFailure(::mapError)
    }

    override suspend fun listCustomResources(
        crd: CustomResourceDefinition,
        namespace: String,
    ): Result<List<CustomResource>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = requireNamespaceIfNamespaced(crd, namespace)
            val list = listCustomResourcesRaw(crd, normalizedNamespace)
            list.resources.sortedBy { it.name }
        }.mapFailure { throwable ->
            mapError(
                throwable = throwable,
                crd = crd,
                namespace = namespace,
            )
        }
    }

    override suspend fun getCustomResource(
        crd: CustomResourceDefinition,
        name: String,
        namespace: String,
    ): Result<CustomResource> = withContext(ioDispatcher) {
        runCatching {
            val normalizedName = name.trim().ifEmpty {
                throw IllegalArgumentException("Custom resource name must not be empty")
            }
            val normalizedNamespace = requireNamespaceIfNamespaced(crd, namespace)
            val payload = if (crd.isClusterScoped) {
                CustomObjectsApi(apiClientProvider())
                    .getClusterCustomObject(crd.group, crd.version, crd.plural, normalizedName)
                    .execute()
            } else {
                CustomObjectsApi(apiClientProvider())
                    .getNamespacedCustomObject(crd.group, crd.version, normalizedNamespace, crd.plural, normalizedName)
                    .execute()
            }
            payload.toDomainCustomResource(gson = gson)
        }.mapFailure { throwable ->
            mapError(
                throwable = throwable,
                crd = crd,
                namespace = namespace,
            )
        }
    }

    override suspend fun applyCustomResourceYaml(
        crd: CustomResourceDefinition,
        name: String,
        namespace: String,
        yaml: String,
    ): Result<YamlEditResult> = withContext(ioDispatcher) {
        val normalizedName = name.trim().ifEmpty {
            return@withContext Result.failure(CrdRepositoryException("Custom resource name must not be empty"))
        }

        val normalizedNamespaceResult = runCatching {
            requireNamespaceIfNamespaced(crd, namespace)
        }
        if (normalizedNamespaceResult.isFailure) {
            val cause = normalizedNamespaceResult.exceptionOrNull() ?: IllegalStateException("Invalid namespace")
            return@withContext Result.failure(mapError(cause, crd = crd, namespace = namespace))
        }
        val normalizedNamespace = normalizedNamespaceResult.getOrNull().orEmpty()

        val currentVersion = runCatching {
            fetchCurrentResourceVersion(
                crd = crd,
                namespace = normalizedNamespace,
                name = normalizedName,
            )
        }.getOrNull()

        runCatching {
            val dynamicObject = parseYamlToDynamicObject(
                crd = crd,
                name = normalizedName,
                namespace = normalizedNamespace,
                yaml = yaml,
            )

            val api = DynamicKubernetesApi(
                crd.group,
                crd.version,
                crd.plural,
                apiClientProvider(),
            )
            val response = api.update(dynamicObject)
            if (!response.isSuccess) {
                response.throwsApiException()
            }
            val nextVersion = response.getObject()?.metadata?.resourceVersion
            if (currentVersion != null && currentVersion == nextVersion) {
                YamlEditResult.NoChanges
            } else {
                YamlEditResult.Applied(resourceVersion = nextVersion)
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { throwable ->
                when (throwable) {
                    is ApiException -> {
                        if (throwable.code == 409) {
                            Result.success(
                                YamlEditResult.Conflict(
                                    currentResourceVersion = runCatching {
                                        fetchCurrentResourceVersion(
                                            crd = crd,
                                            namespace = normalizedNamespace,
                                            name = normalizedName,
                                        )
                                    }.getOrNull(),
                                ),
                            )
                        } else {
                            Result.failure(
                                mapError(
                                    throwable = throwable,
                                    crd = crd,
                                    namespace = normalizedNamespace,
                                ),
                            )
                        }
                    }

                    else -> Result.failure(
                        mapError(
                            throwable = throwable,
                            crd = crd,
                            namespace = normalizedNamespace,
                        ),
                    )
                }
            },
        )
    }

    override fun watchCustomResources(
        crd: CustomResourceDefinition,
        namespace: String,
    ): Flow<Result<List<CustomResource>>> = callbackFlow {
        val activeWatchSession = AtomicReference<CrdWatchSession?>(null)
        val normalizedNamespaceResult = runCatching {
            requireNamespaceIfNamespaced(crd, namespace)
        }
        if (normalizedNamespaceResult.isFailure) {
            trySend(Result.failure(normalizedNamespaceResult.exceptionOrNull() ?: IllegalStateException("Invalid namespace")))
            close()
            return@callbackFlow
        }
        val normalizedNamespace = normalizedNamespaceResult.getOrNull().orEmpty()

        val watcherJob = launch(ioDispatcher) {
            var reconnectDelayMillis = WATCH_RECONNECT_INITIAL_BACKOFF_MILLIS
            var resourceVersion: String? = null
            var shouldReconnect = true

            while (isActive && shouldReconnect) {
                try {
                    val initial = listCustomResourcesRaw(crd, normalizedNamespace)
                    resourceVersion = initial.resourceVersion
                    val state = initial.resources.associateBy { it.name }.toMutableMap()
                    trySend(Result.success(state.values.sortedBy { it.name }))

                    openWatch(crd, normalizedNamespace, resourceVersion).use { watchSession ->
                        activeWatchSession.set(watchSession)
                        reconnectDelayMillis = WATCH_RECONNECT_INITIAL_BACKOFF_MILLIS

                        for (event in watchSession) {
                            if (!isActive) break
                            resourceVersion = event.resourceVersion ?: resourceVersion

                            when (event.type?.uppercase()) {
                                "ADDED", "MODIFIED" -> {
                                    event.resource?.let { state[it.name] = it }
                                    trySend(Result.success(state.values.sortedBy { it.name }))
                                }

                                "DELETED" -> {
                                    event.resource?.name?.let { state.remove(it) }
                                    trySend(Result.success(state.values.sortedBy { it.name }))
                                }

                                "BOOKMARK" -> Unit
                                else -> Unit
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable

                    val mapped = mapError(
                        throwable = throwable,
                        crd = crd,
                        namespace = normalizedNamespace,
                    )
                    trySend(Result.failure(mapped))

                    if (mapped is CrdPermissionDeniedException) {
                        shouldReconnect = false
                        break
                    }

                    delay(reconnectDelayMillis)
                    reconnectDelayMillis = (reconnectDelayMillis * 2)
                        .coerceAtMost(WATCH_RECONNECT_MAX_BACKOFF_MILLIS)
                } finally {
                    activeWatchSession.set(null)
                }
            }
        }

        awaitClose {
            activeWatchSession.getAndSet(null)?.close()
            watcherJob.cancel()
        }
    }.flowOn(ioDispatcher)

    override fun observeFavouriteCrds(): Flow<List<CustomResourceDefinition>> {
        return favouriteDao.observeAll().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun setFavourite(
        crd: CustomResourceDefinition,
        favourite: Boolean,
    ) {
        withContext(ioDispatcher) {
            if (favourite) {
                favouriteDao.upsert(crd.toEntity())
            } else {
                favouriteDao.delete(crd.group, crd.version, crd.kind)
            }
        }
    }

    override suspend fun isFavourite(crd: CustomResourceDefinition): Boolean {
        return withContext(ioDispatcher) {
            favouriteDao.exists(crd.group, crd.version, crd.kind)
        }
    }

    private fun requireNamespaceIfNamespaced(
        crd: CustomResourceDefinition,
        namespace: String,
    ): String {
        if (crd.isClusterScoped) return ""
        return namespace.trim().ifEmpty {
            throw CrdScopeMismatchException(
                crd = crd,
                namespace = namespace,
                message = "Namespace is required for namespaced CRD ${crd.name}",
            )
        }
    }

    private fun listCustomResourcesRaw(
        crd: CustomResourceDefinition,
        namespace: String,
    ): CustomResourceListSnapshot {
        val payload = if (crd.isClusterScoped) {
            CustomObjectsApi(apiClientProvider())
                .listClusterCustomObject(crd.group, crd.version, crd.plural)
                .execute()
        } else {
            CustomObjectsApi(apiClientProvider())
                .listNamespacedCustomObject(crd.group, crd.version, namespace, crd.plural)
                .execute()
        }

        val map = payload as? Map<*, *> ?: emptyMap<String, Any?>()
        val metadata = map["metadata"] as? Map<*, *>
        val resourceVersion = metadata?.get("resourceVersion")?.toString()
        val items = (map["items"] as? List<*>)
            .orEmpty()
            .mapNotNull { it.toDomainCustomResourceOrNull(gson) }

        return CustomResourceListSnapshot(resources = items, resourceVersion = resourceVersion)
    }

    private fun parseYamlToDynamicObject(
        crd: CustomResourceDefinition,
        name: String,
        namespace: String,
        yaml: String,
    ): DynamicKubernetesObject {
        val raw = try {
            Yaml.load(yaml)
        } catch (throwable: Throwable) {
            throw CrdRepositoryException("Invalid YAML content", throwable)
        }

        val jsonObject = try {
            gson.toJsonTree(raw).asJsonObject
        } catch (throwable: Throwable) {
            throw CrdRepositoryException("YAML content must describe a Kubernetes object", throwable)
        }

        val dynamicObject = try {
            DynamicKubernetesObject(jsonObject)
        } catch (throwable: Throwable) {
            throw CrdRepositoryException("YAML content is not a valid Kubernetes resource", throwable)
        }

        val yamlKind = dynamicObject.kind?.trim().orEmpty()
        if (!yamlKind.equals(crd.kind, ignoreCase = true)) {
            throw CrdRepositoryException("YAML kind '$yamlKind' does not match expected '${crd.kind}'")
        }

        val yamlApiVersion = dynamicObject.apiVersion?.trim().orEmpty()
        val expectedApiVersion = "${crd.group}/${crd.version}"
        if (yamlApiVersion != expectedApiVersion) {
            throw CrdRepositoryException(
                "YAML apiVersion '$yamlApiVersion' does not match expected '$expectedApiVersion'",
            )
        }

        val metadata = dynamicObject.metadata ?: V1ObjectMeta().also { dynamicObject.metadata = it }
        val yamlName = metadata.name?.trim().orEmpty()
        if (yamlName.isBlank()) {
            metadata.name = name
        } else if (yamlName != name) {
            throw CrdRepositoryException("YAML metadata.name '$yamlName' does not match '$name'")
        }

        if (crd.isClusterScoped) {
            metadata.namespace = null
        } else {
            val yamlNamespace = metadata.namespace?.trim().orEmpty()
            if (yamlNamespace.isBlank()) {
                metadata.namespace = namespace
            } else if (yamlNamespace != namespace) {
                throw CrdScopeMismatchException(
                    crd = crd,
                    namespace = namespace,
                    message = "YAML metadata.namespace '$yamlNamespace' does not match '$namespace'",
                )
            }
        }

        return dynamicObject
    }

    private fun fetchCurrentResourceVersion(
        crd: CustomResourceDefinition,
        namespace: String,
        name: String,
    ): String? {
        return if (crd.isClusterScoped) {
            CustomObjectsApi(apiClientProvider())
                .getClusterCustomObject(crd.group, crd.version, crd.plural, name)
                .execute()
                .resourceVersionOrNull()
        } else {
            CustomObjectsApi(apiClientProvider())
                .getNamespacedCustomObject(crd.group, crd.version, namespace, crd.plural, name)
                .execute()
                .resourceVersionOrNull()
        }
    }

    private fun openWatch(
        crd: CustomResourceDefinition,
        namespace: String,
        resourceVersion: String?,
    ): CrdWatchSession {
        val apiClient = apiClientProvider()
        val customApi = CustomObjectsApi(apiClient)
        val call: Call = if (crd.isClusterScoped) {
            val request = customApi.listClusterCustomObject(crd.group, crd.version, crd.plural)
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                request.resourceVersion(resourceVersion)
            }
            request.buildCall(null)
        } else {
            val request = customApi.listNamespacedCustomObject(crd.group, crd.version, namespace, crd.plural)
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                request.resourceVersion(resourceVersion)
            }
            request.buildCall(null)
        }

        val watch: Watch<Any> = Watch.createWatch(
            apiClient,
            call,
            object : TypeToken<Watch.Response<Any>>() {}.type,
        )
        return KubernetesCrdWatchSession(watch = watch, gson = gson)
    }

    private fun mapError(
        throwable: Throwable,
        crd: CustomResourceDefinition? = null,
        namespace: String? = null,
    ): Throwable {
        if (throwable is CrdRepositoryException) return throwable

        return when (throwable) {
            is ApiException -> when (throwable.code) {
                403 -> CrdPermissionDeniedException(
                    crd = crd,
                    namespace = namespace,
                    cause = throwable,
                )

                404 -> CrdNotFoundException(crd = crd, cause = throwable)
                else -> CrdRepositoryException(
                    message = "CRD API returned HTTP ${throwable.code}",
                    cause = throwable,
                )
            }

            else -> CrdRepositoryException(
                message = throwable.message ?: "CRD request failed",
                cause = throwable,
            )
        }
    }

    private data class CustomResourceListSnapshot(
        val resources: List<CustomResource>,
        val resourceVersion: String?,
    )

    private object FilePaths {
        fun kubeConfigPath(context: Context): Path = context.filesDir.toPath().resolve("kube/config")
    }

    companion object {
        private val gson = Gson()
        private const val WATCH_TIMEOUT_SECONDS = 300
        private const val WATCH_RECONNECT_INITIAL_BACKOFF_MILLIS = 1_000L
        private const val WATCH_RECONNECT_MAX_BACKOFF_MILLIS = 30_000L
    }
}

open class CrdRepositoryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class CrdNotFoundException(
    val crd: CustomResourceDefinition?,
    cause: Throwable? = null,
) : CrdRepositoryException(
    message = if (crd != null) {
        "CRD ${crd.name} was not found"
    } else {
        "CRD was not found"
    },
    cause = cause,
)

class CrdPermissionDeniedException(
    val crd: CustomResourceDefinition?,
    val namespace: String?,
    cause: Throwable? = null,
) : CrdRepositoryException(
    message = if (crd != null) {
        "Permission denied for CRD ${crd.name}${namespace?.takeIf { it.isNotBlank() }?.let { " in namespace $it" } ?: ""}"
    } else {
        "Permission denied while accessing CRDs"
    },
    cause = cause,
)

class CrdScopeMismatchException(
    val crd: CustomResourceDefinition?,
    val namespace: String?,
    message: String,
    cause: Throwable? = null,
) : CrdRepositoryException(
    message = message,
    cause = cause,
)

private interface CrdWatchSession : Iterable<CrdWatchEvent>, AutoCloseable

private data class CrdWatchEvent(
    val type: String?,
    val resource: CustomResource?,
    val resourceVersion: String?,
)

private class KubernetesCrdWatchSession(
    private val watch: Watch<Any>,
    private val gson: Gson,
) : CrdWatchSession {
    override fun iterator(): Iterator<CrdWatchEvent> {
        val delegate = watch.iterator()
        return object : Iterator<CrdWatchEvent> {
            override fun hasNext(): Boolean = delegate.hasNext()

            override fun next(): CrdWatchEvent {
                val response = delegate.next()
                val payload = response.`object`
                val resource = payload.toDomainCustomResourceOrNull(gson)
                return CrdWatchEvent(
                    type = response.type,
                    resource = resource,
                    resourceVersion = payload.resourceVersionOrNull(),
                )
            }
        }
    }

    override fun close() {
        watch.close()
    }
}

private fun V1CustomResourceDefinition.toDomainOrNull(): CustomResourceDefinition? {
    val metadataName = metadata?.name?.trim().orEmpty()
    val spec = spec ?: return null
    val group = spec.group?.trim().orEmpty()
    val kind = spec.names?.kind?.trim().orEmpty()
    val scope = spec.scope?.trim().orEmpty()
    val version = spec.versions
        ?.firstOrNull { it.storage == true }
        ?.name
        ?.trim()
        .orEmpty()
        .ifEmpty {
            spec.versions
                ?.firstOrNull { it.served == true }
                ?.name
                ?.trim()
                .orEmpty()
        }

    if (metadataName.isEmpty() || group.isEmpty() || kind.isEmpty() || scope.isEmpty() || version.isEmpty()) {
        return null
    }

    return CustomResourceDefinition(
        name = metadataName,
        group = group,
        version = version,
        scope = scope,
        kind = kind,
    )
}

private val CustomResourceDefinition.isClusterScoped: Boolean
    get() = scope.equals("Cluster", ignoreCase = true)

private val CustomResourceDefinition.plural: String
    get() = name.substringBefore('.').ifBlank { name.lowercase() }

private fun FavouriteCrd.toDomain(): CustomResourceDefinition {
    return CustomResourceDefinition(
        name = name,
        group = group,
        version = version,
        scope = scope,
        kind = kind,
    )
}

private fun CustomResourceDefinition.toEntity(): FavouriteCrd {
    return FavouriteCrd(
        name = name,
        group = group,
        version = version,
        scope = scope,
        kind = kind,
    )
}

private fun CustomResourceDefinition.favouriteKey(): String = "$group/$version/$kind"

private fun FavouriteCrd.favouriteKey(): String = "$group/$version/$kind"

private fun Any?.toDomainCustomResource(gson: Gson): CustomResource {
    return toDomainCustomResourceOrNull(gson)
        ?: throw IllegalStateException("Custom resource payload is missing metadata.name")
}

private fun Any?.toDomainCustomResourceOrNull(gson: Gson): CustomResource? {
    val map = this as? Map<*, *> ?: return null
    val metadata = map["metadata"] as? Map<*, *> ?: return null
    val name = metadata["name"]?.toString()?.trim().orEmpty().ifEmpty { return null }
    val namespace = metadata["namespace"]?.toString().orEmpty()
    return CustomResource(
        name = name,
        namespace = namespace,
        rawJson = gson.toJson(map),
    )
}

private fun Any?.resourceVersionOrNull(): String? {
    val map = this as? Map<*, *> ?: return null
    val metadata = map["metadata"] as? Map<*, *> ?: return null
    return metadata["resourceVersion"]?.toString()
}

private fun buildApiClient(kubeConfigPath: Path): ApiClient {
    if (!Files.exists(kubeConfigPath)) {
        throw CrdRepositoryException("Kubeconfig file not found at $kubeConfigPath")
    }

    val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    val kubeConfig = try {
        KubeConfig.loadKubeConfig(StringReader(raw))
    } catch (throwable: Throwable) {
        throw CrdRepositoryException("Invalid kubeconfig content", throwable)
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
