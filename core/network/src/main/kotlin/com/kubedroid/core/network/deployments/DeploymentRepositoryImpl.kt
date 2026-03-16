package com.kubedroid.core.network.deployments

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSerializer
import com.google.gson.JsonPrimitive
import com.kubedroid.core.database.cache.CacheRepository
import com.kubedroid.core.database.cache.CachedDeployment
import com.kubedroid.core.database.cache.StaleDataIndicator
import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.AppsV1Api
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1PodTemplateSpec
import io.kubernetes.client.openapi.models.V1ReplicaSet
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.PatchUtils
import io.kubernetes.client.util.Watch
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kubernetes API-backed [DeploymentRepository] implementation.
 */
class DeploymentRepositoryImpl(
    private val apiClientProvider: () -> ApiClient,
    private val cacheRepository: CacheRepository? = null,
    private val contextNameProvider: () -> String = { DEFAULT_CONTEXT_NAME },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchReconnectInitialBackoffMillis: Long = 1_000L,
    private val watchReconnectMaxBackoffMillis: Long = 30_000L,
    private val deploymentApiFactory: DeploymentApiFactory = DeploymentApiFactory.Default,
) : DeploymentRepository {

    constructor(
        kubeConfigPath: Path,
        cacheRepository: CacheRepository? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        watchReconnectInitialBackoffMillis: Long = 1_000L,
        watchReconnectMaxBackoffMillis: Long = 30_000L,
        deploymentApiFactory: DeploymentApiFactory = DeploymentApiFactory.Default,
    ) : this(
        apiClientProvider = { buildDeploymentApiClient(kubeConfigPath) },
        cacheRepository = cacheRepository,
        contextNameProvider = { readCurrentContextName(kubeConfigPath) },
        ioDispatcher = ioDispatcher,
        watchReconnectInitialBackoffMillis = watchReconnectInitialBackoffMillis,
        watchReconnectMaxBackoffMillis = watchReconnectMaxBackoffMillis,
        deploymentApiFactory = deploymentApiFactory,
    )

    override suspend fun list(namespace: String): Result<DeploymentListResult> = withContext(ioDispatcher) {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Namespace must not be empty"))
        }
        val contextName = contextNameProvider()
        val cachedDeployments = getCachedDeployments(
            cacheRepository = cacheRepository,
            contextName = contextName,
            namespace = normalizedNamespace,
        )
        val deploymentApi = deploymentApiFactory.create(apiClientProvider)
        val liveResult = runCatching {
            deploymentApi.listDeployments(normalizedNamespace).sortedBy { it.name }
        }

        liveResult.fold(
            onSuccess = { liveDeployments ->
                val fetchedAt = System.currentTimeMillis()
                cacheDeployments(
                    cacheRepository = cacheRepository,
                    contextName = contextName,
                    namespace = normalizedNamespace,
                    deployments = liveDeployments,
                    fetchedAt = fetchedAt,
                )
                Result.success(liveDeployments.toLiveDeploymentListResult(fetchedAt))
            },
            onFailure = { throwable ->
                if (cachedDeployments != null) {
                    Result.success(cachedDeployments)
                } else {
                    Result.failure(throwable)
                }
            },
        )
    }

    override fun watch(namespace: String): Flow<Result<DeploymentListResult>> = callbackFlow {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isEmpty()) {
            trySend(Result.failure(IllegalArgumentException("Namespace must not be empty")))
            close()
            return@callbackFlow
        }

        val watcherJob = launch(ioDispatcher) {
            val deploymentApi = deploymentApiFactory.create(apiClientProvider)
            var reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)
            var resourceVersion: String? = null
            var shouldReconnect = true
            val contextName = contextNameProvider()
            getCachedDeployments(
                cacheRepository = cacheRepository,
                contextName = contextName,
                namespace = normalizedNamespace,
            )?.let { trySend(Result.success(it)) }

            while (isActive && shouldReconnect) {
                try {
                    val initial = deploymentApi.listDeploymentsSnapshot(normalizedNamespace)
                    resourceVersion = initial.resourceVersion
                    val state = initial.deployments.associateBy { it.namespacedKey() }.toMutableMap()
                    cacheDeployments(
                        cacheRepository = cacheRepository,
                        contextName = contextName,
                        namespace = normalizedNamespace,
                        deployments = state.sortedDeployments(),
                        fetchedAt = System.currentTimeMillis(),
                    )
                    trySend(Result.success(state.sortedDeployments().toLiveDeploymentListResult()))

                    deploymentApi.openDeploymentWatch(normalizedNamespace, resourceVersion).use { watchSession ->
                        reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)

                        for (event in watchSession) {
                            if (!isActive) break
                            resourceVersion = event.resourceVersion ?: resourceVersion

                            when (event.type?.uppercase()) {
                                "ADDED", "MODIFIED" -> {
                                    event.deployment?.let { state[it.namespacedKey()] = it }
                                    cacheDeployments(
                                        cacheRepository = cacheRepository,
                                        contextName = contextName,
                                        namespace = normalizedNamespace,
                                        deployments = state.sortedDeployments(),
                                        fetchedAt = System.currentTimeMillis(),
                                    )
                                    trySend(Result.success(state.sortedDeployments().toLiveDeploymentListResult()))
                                }

                                "DELETED" -> {
                                    event.deployment?.let { state.remove(it.namespacedKey()) }
                                    cacheDeployments(
                                        cacheRepository = cacheRepository,
                                        contextName = contextName,
                                        namespace = normalizedNamespace,
                                        deployments = state.sortedDeployments(),
                                        fetchedAt = System.currentTimeMillis(),
                                    )
                                    trySend(Result.success(state.sortedDeployments().toLiveDeploymentListResult()))
                                }

                                "BOOKMARK" -> Unit
                                else -> Unit
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    val cachedFallback = getCachedDeployments(
                        cacheRepository = cacheRepository,
                        contextName = contextName,
                        namespace = normalizedNamespace,
                    )
                    if (cachedFallback != null) {
                        trySend(Result.success(cachedFallback))
                    } else {
                        trySend(Result.failure(throwable))
                    }
                    if ((throwable as? ApiException)?.code == 403) {
                        shouldReconnect = false
                        break
                    }
                    delay(reconnectDelayMillis)
                    reconnectDelayMillis = (reconnectDelayMillis * 2).coerceAtMost(
                        watchReconnectMaxBackoffMillis.coerceAtLeast(reconnectDelayMillis),
                    )
                }
            }
        }

        awaitClose { watcherJob.cancel() }
    }.flowOn(ioDispatcher)

    override suspend fun scale(
        namespace: String,
        deploymentName: String,
        replicas: Int,
    ): ScaleResult = withContext(ioDispatcher) {
        if (replicas < 0) {
            return@withContext ScaleResult.InvalidReplicaCount
        }

        val normalizedNamespace = namespace.trim()
        val normalizedName = deploymentName.trim()
        if (normalizedNamespace.isEmpty() || normalizedName.isEmpty()) {
            return@withContext ScaleResult.Failure(IllegalArgumentException("Namespace and deployment name are required"))
        }

        val deploymentApi = deploymentApiFactory.create(apiClientProvider)
        runCatching {
            val existing = deploymentApi.readDeployment(normalizedNamespace, normalizedName)
            val previousReplicas = existing.spec?.replicas ?: 0
            val patchBody = """{"spec":{"replicas":$replicas}}"""
            val patched = deploymentApi.patchDeployment(
                namespace = normalizedNamespace,
                deploymentName = normalizedName,
                patchBody = patchBody,
            )
            ScaleResult.Success(
                namespace = normalizedNamespace,
                deploymentName = normalizedName,
                previousReplicas = previousReplicas,
                requestedReplicas = replicas,
                currentReplicas = patched.spec?.replicas ?: replicas,
            )
        }.getOrElse { throwable ->
            when ((throwable as? ApiException)?.code) {
                403 -> ScaleResult.Forbidden
                404 -> ScaleResult.NotFound
                409 -> ScaleResult.Conflict
                else -> ScaleResult.Failure(throwable)
            }
        }
    }

    override suspend fun history(
        namespace: String,
        deploymentName: String,
    ): Result<List<RolloutRevision>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = namespace.requireNamespace()
            val normalizedName = deploymentName.requireDeploymentName()
            deploymentApiFactory
                .create(apiClientProvider)
                .listReplicaSets(normalizedNamespace)
                .asSequence()
                .filter { it.isOwnedByDeployment(normalizedName) }
                .mapNotNull { it.toRolloutRevisionOrNull() }
                .sortedByDescending { it.revision }
                .toList()
        }
    }

    override suspend fun rollback(
        namespace: String,
        deploymentName: String,
        toRevision: Long?,
    ): RolloutResult = withContext(ioDispatcher) {
        val normalizedNamespace = namespace.trim()
        val normalizedName = deploymentName.trim()
        if (normalizedNamespace.isEmpty() || normalizedName.isEmpty()) {
            return@withContext RolloutResult.Failure(
                IllegalArgumentException("Namespace and deployment name are required"),
            )
        }

        val deploymentApi = deploymentApiFactory.create(apiClientProvider)
        val replicaSets = runCatching {
            deploymentApi
                .listReplicaSets(normalizedNamespace)
                .asSequence()
                .filter { it.isOwnedByDeployment(normalizedName) }
                .toList()
        }.getOrElse { throwable ->
            return@withContext RolloutResult.Failure(throwable)
        }

        val availableRevisions = replicaSets
            .asSequence()
            .mapNotNull { it.rolloutRevisionOrNull() }
            .distinct()
            .sortedDescending()
            .toList()

        val targetRevision = if (toRevision != null) {
            toRevision
        } else {
            availableRevisions.getOrNull(1) ?: availableRevisions.firstOrNull() ?: return@withContext RolloutResult.Failure(
                IllegalStateException("No rollout revision is available for rollback"),
            )
        }

        val targetTemplate = replicaSets
            .asSequence()
            .filter { it.rolloutRevisionOrNull() == targetRevision }
            .maxByOrNull { it.metadata?.creationTimestamp?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE }
            ?.spec
            ?.template
            ?: return@withContext RolloutResult.Failure(
                IllegalStateException("Target rollout revision $targetRevision was not found"),
            )

        val patchBody = """{"spec":{"template":${targetTemplate.toJsonPatchFragment()}}}"""

        patchRollout(
            namespace = normalizedNamespace,
            deploymentName = normalizedName,
            action = RolloutResult.Action.ROLLBACK,
            targetRevision = targetRevision,
            patchBody = patchBody,
        )
    }

    override suspend fun pause(
        namespace: String,
        deploymentName: String,
    ): RolloutResult = patchRollout(
        namespace = namespace.trim(),
        deploymentName = deploymentName.trim(),
        action = RolloutResult.Action.PAUSE,
        targetRevision = null,
        patchBody = """{"spec":{"paused":true}}""",
    )

    override suspend fun resume(
        namespace: String,
        deploymentName: String,
    ): RolloutResult = patchRollout(
        namespace = namespace.trim(),
        deploymentName = deploymentName.trim(),
        action = RolloutResult.Action.RESUME,
        targetRevision = null,
        patchBody = """{"spec":{"paused":false}}""",
    )

    private suspend fun patchRollout(
        namespace: String,
        deploymentName: String,
        action: RolloutResult.Action,
        targetRevision: Long?,
        patchBody: String,
    ): RolloutResult = withContext(ioDispatcher) {
        if (namespace.isEmpty() || deploymentName.isEmpty()) {
            return@withContext RolloutResult.Failure(
                IllegalArgumentException("Namespace and deployment name are required"),
            )
        }

        runCatching {
            deploymentApiFactory
                .create(apiClientProvider)
                .patchDeployment(
                    namespace = namespace,
                    deploymentName = deploymentName,
                    patchBody = patchBody,
                )
            RolloutResult.Success(
                namespace = namespace,
                deploymentName = deploymentName,
                action = action,
                targetRevision = targetRevision,
            )
        }.getOrElse { throwable ->
            when ((throwable as? ApiException)?.code) {
                403 -> RolloutResult.Forbidden
                404 -> RolloutResult.NotFound
                409 -> RolloutResult.Conflict
                else -> RolloutResult.Failure(throwable)
            }
        }
    }
}

fun interface DeploymentApiFactory {
    fun create(apiClientProvider: () -> ApiClient): DeploymentApi

    data object Default : DeploymentApiFactory {
        override fun create(apiClientProvider: () -> ApiClient): DeploymentApi = KubernetesDeploymentApi(apiClientProvider)
    }
}

interface DeploymentApi {
    fun readDeployment(namespace: String, deploymentName: String): V1Deployment
    fun listDeployments(namespace: String): List<Deployment>
    fun listDeploymentsSnapshot(namespace: String): DeploymentListSnapshot
    fun listReplicaSets(namespace: String): List<V1ReplicaSet>
    fun openDeploymentWatch(namespace: String, resourceVersion: String?): DeploymentWatchSession
    fun patchDeployment(namespace: String, deploymentName: String, patchBody: String): V1Deployment
}

data class DeploymentListSnapshot(
    val deployments: List<Deployment>,
    val resourceVersion: String?,
)

data class DeploymentWatchEvent(
    val type: String?,
    val deployment: Deployment?,
    val resourceVersion: String?,
)

interface DeploymentWatchSession : Iterable<DeploymentWatchEvent>, AutoCloseable

private class KubernetesDeploymentApi(
    private val apiClientProvider: () -> ApiClient,
) : DeploymentApi {

    override fun readDeployment(namespace: String, deploymentName: String): V1Deployment {
        return appsV1Api()
            .readNamespacedDeployment(deploymentName, namespace)
            .execute()
    }

    override fun listDeployments(namespace: String): List<Deployment> {
        return listDeploymentsSnapshot(namespace).deployments
    }

    override fun listDeploymentsSnapshot(namespace: String): DeploymentListSnapshot {
        val response = if (namespace.isAllNamespacesSelection()) {
            appsV1Api().listDeploymentForAllNamespaces().execute()
        } else {
            appsV1Api().listNamespacedDeployment(namespace).execute()
        }

        val fallbackNamespace = if (namespace.isAllNamespacesSelection()) null else namespace

        return DeploymentListSnapshot(
            deployments = response.items.orEmpty().map { it.toDomainDeployment(fallbackNamespace) },
            resourceVersion = response.metadata?.resourceVersion,
        )
    }

    override fun listReplicaSets(namespace: String): List<V1ReplicaSet> {
        return appsV1Api()
            .listNamespacedReplicaSet(namespace)
            .execute()
            .items
            .orEmpty()
    }

    override fun openDeploymentWatch(namespace: String, resourceVersion: String?): DeploymentWatchSession {
        val api = appsV1Api()
        val call = if (namespace.isAllNamespacesSelection()) {
            val requestBuilder = api.listDeploymentForAllNamespaces()
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        } else {
            val requestBuilder = api.listNamespacedDeployment(namespace)
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        }
        val watch: Watch<V1Deployment> = Watch.createWatch(
            apiClientProvider(),
            call,
            watchType,
        )
        return KubernetesDeploymentWatchSession(watch)
    }

    override fun patchDeployment(namespace: String, deploymentName: String, patchBody: String): V1Deployment {
        val apiClient = apiClientProvider()
        val appsV1Api = AppsV1Api(apiClient)
        return PatchUtils.patch(
            V1Deployment::class.java,
            {
                appsV1Api
                    .patchNamespacedDeployment(deploymentName, namespace, V1Patch(patchBody))
                    .buildCall(null)
            },
            V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
            apiClient,
        )
    }

    private fun appsV1Api(): AppsV1Api = AppsV1Api(apiClientProvider())

    private companion object {
        val watchType = object : com.google.gson.reflect.TypeToken<Watch.Response<V1Deployment>>() {}.type
    }
}

private class KubernetesDeploymentWatchSession(
    private val watch: Watch<V1Deployment>,
) : DeploymentWatchSession {
    override fun iterator(): Iterator<DeploymentWatchEvent> {
        val delegate = watch.iterator()
        return object : Iterator<DeploymentWatchEvent> {
            override fun hasNext(): Boolean = delegate.hasNext()

            override fun next(): DeploymentWatchEvent {
                val event = delegate.next()
                val deployment = event.`object`
                return DeploymentWatchEvent(
                    type = event.type,
                    deployment = deployment?.toDomainDeployment(),
                    resourceVersion = deployment?.metadata?.resourceVersion,
                )
            }
        }
    }

    override fun close() {
        watch.close()
    }
}

private fun V1Deployment.toDomainDeployment(fallbackNamespace: String? = null): Deployment {
    val metadata = metadata
    val status = status
    val spec = spec
    return Deployment(
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace ?: fallbackNamespace.orEmpty(),
        desiredReplicas = spec?.replicas ?: 0,
        readyReplicas = status?.readyReplicas ?: 0,
        updatedReplicas = status?.updatedReplicas ?: 0,
        availableReplicas = status?.availableReplicas ?: 0,
        observedGeneration = status?.observedGeneration,
    )
}

private fun V1ReplicaSet.isOwnedByDeployment(deploymentName: String): Boolean {
    return metadata
        ?.ownerReferences
        .orEmpty()
        .any { owner ->
            owner?.kind.equals("Deployment", ignoreCase = true) &&
                owner?.name == deploymentName
        }
}

private fun V1ReplicaSet.toRolloutRevisionOrNull(): RolloutRevision? {
    val annotations = metadata?.annotations.orEmpty()
    val revision = annotations[DEPLOYMENT_REVISION_ANNOTATION]?.toLongOrNull() ?: return null
    return RolloutRevision(
        revision = revision,
        changeCause = annotations[CHANGE_CAUSE_ANNOTATION],
        createdAtEpochMillis = metadata?.creationTimestamp?.toInstant()?.toEpochMilli(),
    )
}

private fun V1ReplicaSet.rolloutRevisionOrNull(): Long? {
    return metadata?.annotations?.get(DEPLOYMENT_REVISION_ANNOTATION)?.toLongOrNull()
}

private fun V1PodTemplateSpec.toJsonPatchFragment(): String {
    return deploymentPatchGson.toJson(this)
}

private fun String.requireNamespace(): String {
    if (isBlank()) throw IllegalArgumentException("Namespace must not be empty")
    return trim()
}

private fun String.requireDeploymentName(): String {
    if (isBlank()) throw IllegalArgumentException("Deployment name must not be empty")
    return trim()
}

private fun Map<String, Deployment>.sortedDeployments(): List<Deployment> = values.sortedBy { it.name }

private fun Deployment.namespacedKey(): String = "${namespace.trim()}/${name.trim()}"

private suspend fun getCachedDeployments(
    cacheRepository: CacheRepository?,
    contextName: String,
    namespace: String,
): DeploymentListResult? {
    val repository = cacheRepository ?: return null
    val cachedNamespace = if (namespace.isAllNamespacesSelection()) null else namespace
    val cachedResources = repository.getCachedResources(
        contextName = contextName,
        namespace = cachedNamespace,
    ).getOrNull() ?: return null

    if (cachedResources.deployments.isEmpty()) return null
    return DeploymentListResult(
        deployments = cachedResources.deployments.map { it.toDomainDeployment() }.sortedBy { it.name },
        staleDataIndicator = deploymentStaleIndicator(cachedResources.deployments),
    )
}

private suspend fun cacheDeployments(
    cacheRepository: CacheRepository?,
    contextName: String,
    namespace: String,
    deployments: List<Deployment>,
    fetchedAt: Long,
) {
    val repository = cacheRepository ?: return
    repository.cacheResources(
        contextName = contextName,
        deployments = deployments.map { deployment ->
            CachedDeployment(
                contextName = contextName,
                namespace = deployment.namespace,
                name = deployment.name,
                desiredReplicas = deployment.desiredReplicas,
                availableReplicas = deployment.availableReplicas,
                lastFetchedAt = fetchedAt,
            )
        },
        fetchedAt = fetchedAt,
    )
}

private fun CachedDeployment.toDomainDeployment(): Deployment = Deployment(
    name = name,
    namespace = namespace,
    desiredReplicas = desiredReplicas,
    readyReplicas = availableReplicas,
    updatedReplicas = availableReplicas,
    availableReplicas = availableReplicas,
    observedGeneration = null,
)

private fun deploymentStaleIndicator(cachedDeployments: List<CachedDeployment>): StaleDataIndicator {
    val lastFetchedAt = cachedDeployments.maxOfOrNull { it.lastFetchedAt }
    val isFresh = lastFetchedAt != null && (System.currentTimeMillis() - lastFetchedAt) <= DEPLOYMENT_CACHE_TTL_MILLIS
    return StaleDataIndicator(
        isFresh = isFresh,
        lastFetchedAt = lastFetchedAt,
        source = StaleDataIndicator.Source.Cache,
    )
}

private fun List<Deployment>.toLiveDeploymentListResult(
    fetchedAt: Long = System.currentTimeMillis(),
): DeploymentListResult = DeploymentListResult(
    deployments = this,
    staleDataIndicator = StaleDataIndicator(
        isFresh = true,
        lastFetchedAt = fetchedAt,
        source = StaleDataIndicator.Source.Live,
    ),
)

private fun buildDeploymentApiClient(kubeConfigPath: Path): ApiClient {
    if (!Files.exists(kubeConfigPath)) {
        throw IllegalStateException("Kubeconfig file not found at $kubeConfigPath")
    }
    val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    val kubeConfig = KubeConfig.loadKubeConfig(StringReader(raw))
    return ClientBuilder.kubeconfig(kubeConfig)
        .build()
        .setLenientOnJson(true)
}

private fun readCurrentContextName(kubeConfigPath: Path): String {
    if (!Files.exists(kubeConfigPath)) return DEFAULT_CONTEXT_NAME
    val raw = runCatching { Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8) }
        .getOrNull()
        ?: return DEFAULT_CONTEXT_NAME
    return runCatching { KubeConfig.loadKubeConfig(StringReader(raw)).currentContext }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_CONTEXT_NAME
}

private const val DEPLOYMENT_REVISION_ANNOTATION = "deployment.kubernetes.io/revision"
private const val CHANGE_CAUSE_ANNOTATION = "kubernetes.io/change-cause"
private const val WATCH_TIMEOUT_SECONDS = 30
private const val DEPLOYMENT_CACHE_TTL_MILLIS = 60_000L
private const val DEFAULT_CONTEXT_NAME = "default"

private val deploymentPatchGson: Gson = GsonBuilder()
    .registerTypeAdapter(
        OffsetDateTime::class.java,
        JsonSerializer<OffsetDateTime> { source, _, _ ->
            if (source == null) null else JsonPrimitive(source.toString())
        },
    )
    .create()
