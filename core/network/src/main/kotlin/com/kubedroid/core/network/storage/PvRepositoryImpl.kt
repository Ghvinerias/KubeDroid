package com.kubedroid.core.network.storage

import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.apis.StorageV1Api
import io.kubernetes.client.openapi.models.V1PersistentVolume
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim
import io.kubernetes.client.openapi.models.V1StorageClass
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Watch
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
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
 * Kubernetes API-backed [PvRepository] implementation.
 */
class PvRepositoryImpl(
    private val apiClientProvider: () -> ApiClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchReconnectInitialBackoffMillis: Long = 1_000L,
    private val watchReconnectMaxBackoffMillis: Long = 30_000L,
    private val pvApiFactory: PvApiFactory = PvApiFactory.Default,
) : PvRepository {

    constructor(
        kubeConfigPath: Path,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        watchReconnectInitialBackoffMillis: Long = 1_000L,
        watchReconnectMaxBackoffMillis: Long = 30_000L,
        pvApiFactory: PvApiFactory = PvApiFactory.Default,
    ) : this(
        apiClientProvider = { buildPvApiClient(kubeConfigPath) },
        ioDispatcher = ioDispatcher,
        watchReconnectInitialBackoffMillis = watchReconnectInitialBackoffMillis,
        watchReconnectMaxBackoffMillis = watchReconnectMaxBackoffMillis,
        pvApiFactory = pvApiFactory,
    )

    override suspend fun listPvs(): Result<List<PersistentVolume>> = withContext(ioDispatcher) {
        runCatching {
            pvApiFactory
                .create(apiClientProvider)
                .listPvs()
                .map { it.normalize() }
                .sortedBy { it.name }
        }
    }

    override suspend fun listPvcs(namespace: String): Result<List<PersistentVolumeClaim>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = namespace.trim().ifEmpty {
                throw IllegalArgumentException("Namespace must not be empty")
            }
            val pvApi = pvApiFactory.create(apiClientProvider)
            val pvStatusByName = pvApi.listPvs().associate { it.name to it.status }
            pvApi
                .listPvcsSnapshot(normalizedNamespace)
                .pvcs
                .map { it.matchVolume(pvStatusByName) }
                .sortedBy { it.name }
        }
    }

    override fun watchPvcs(namespace: String): Flow<Result<List<PersistentVolumeClaim>>> = callbackFlow {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isEmpty()) {
            trySend(Result.failure(IllegalArgumentException("Namespace must not be empty")))
            close()
            return@callbackFlow
        }

        val watcherJob = launch(ioDispatcher) {
            var reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)
            var resourceVersion: String? = null
            var shouldReconnect = true

            while (isActive && shouldReconnect) {
                val pvApi = pvApiFactory.create(apiClientProvider)
                try {
                    val pvStatusByName = pvApi.listPvs().associate { it.name to it.status }
                    val initial = pvApi.listPvcsSnapshot(normalizedNamespace)
                    resourceVersion = initial.resourceVersion
                    val state = initial
                        .pvcs
                        .map { it.matchVolume(pvStatusByName) }
                        .associateBy { it.namespacedKey() }
                        .toMutableMap()
                    trySend(Result.success(state.sortedPvcs()))

                    pvApi.openPvcWatch(normalizedNamespace, resourceVersion).use { watchSession ->
                        reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)

                        for (event in watchSession) {
                            if (!isActive) break
                            resourceVersion = event.resourceVersion ?: resourceVersion

                            when (event.type?.uppercase()) {
                                "ADDED", "MODIFIED" -> {
                                    event.pvc
                                        ?.matchVolume(pvStatusByName)
                                        ?.let { state[it.namespacedKey()] = it }
                                    trySend(Result.success(state.sortedPvcs()))
                                }

                                "DELETED" -> {
                                    event.pvc?.let { state.remove(it.namespacedKey()) }
                                    trySend(Result.success(state.sortedPvcs()))
                                }

                                "BOOKMARK" -> Unit
                                else -> Unit
                            }
                        }
                    }

                    // Prevent a hot reconnect loop if the watch closes immediately.
                    if (isActive && shouldReconnect) {
                        delay(reconnectDelayMillis)
                    }
                } catch (throwable: Throwable) {
                    trySend(Result.failure(throwable))
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

    override suspend fun listStorageClasses(): Result<List<StorageClass>> = withContext(ioDispatcher) {
        runCatching {
            pvApiFactory
                .create(apiClientProvider)
                .listStorageClasses()
                .sortedBy { it.name }
        }.recoverCatching { throwable ->
            if ((throwable as? ApiException)?.code == 404) {
                emptyList()
            } else {
                throw throwable
            }
        }
    }
}

fun interface PvApiFactory {
    fun create(apiClientProvider: () -> ApiClient): PvApi

    data object Default : PvApiFactory {
        override fun create(apiClientProvider: () -> ApiClient): PvApi = KubernetesPvApi(apiClientProvider)
    }
}

interface PvApi {
    fun listPvs(): List<PersistentVolume>
    fun listPvcsSnapshot(namespace: String): PvcListSnapshot
    fun openPvcWatch(namespace: String, resourceVersion: String?): PvcWatchSession
    fun listStorageClasses(): List<StorageClass>
}

data class PvcListSnapshot(
    val pvcs: List<PersistentVolumeClaim>,
    val resourceVersion: String?,
)

data class PvcWatchEvent(
    val type: String?,
    val pvc: PersistentVolumeClaim?,
    val resourceVersion: String?,
)

interface PvcWatchSession : Iterable<PvcWatchEvent>, AutoCloseable

private class KubernetesPvApi(
    private val apiClientProvider: () -> ApiClient,
) : PvApi {

    override fun listPvs(): List<PersistentVolume> {
        return CoreV1Api(apiClientProvider())
            .listPersistentVolume()
            .execute()
            .items
            .orEmpty()
            .mapNotNull { it.toDomainPv() }
    }

    override fun listPvcsSnapshot(namespace: String): PvcListSnapshot {
        val api = CoreV1Api(apiClientProvider())
        val response = if (namespace.isAllNamespacesSelection()) {
            api.listPersistentVolumeClaimForAllNamespaces().execute()
        } else {
            api.listNamespacedPersistentVolumeClaim(namespace).execute()
        }

        return PvcListSnapshot(
            pvcs = response
                .items
                .orEmpty()
                .mapNotNull { it.toDomainPvc() },
            resourceVersion = response.metadata?.resourceVersion,
        )
    }

    override fun openPvcWatch(namespace: String, resourceVersion: String?): PvcWatchSession {
        val api = CoreV1Api(apiClientProvider())
        val call = if (namespace.isAllNamespacesSelection()) {
            val requestBuilder = api.listPersistentVolumeClaimForAllNamespaces()
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        } else {
            val requestBuilder = api.listNamespacedPersistentVolumeClaim(namespace)
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        }
        val watch: Watch<V1PersistentVolumeClaim> = Watch.createWatch(
            apiClientProvider(),
            call,
            V1_PVC_WATCH_TYPE,
        )

        return KubernetesPvcWatchSession(watch)
    }

    override fun listStorageClasses(): List<StorageClass> {
        return StorageV1Api(apiClientProvider())
            .listStorageClass()
            .execute()
            .items
            .orEmpty()
            .mapNotNull { it.toDomainStorageClass() }
    }
}

private class KubernetesPvcWatchSession(
    private val watch: Watch<V1PersistentVolumeClaim>,
) : PvcWatchSession {
    override fun iterator(): Iterator<PvcWatchEvent> {
        val delegate = watch.iterator()
        return object : Iterator<PvcWatchEvent> {
            override fun hasNext(): Boolean = delegate.hasNext()

            override fun next(): PvcWatchEvent {
                val event = delegate.next()
                val obj = event.`object`
                return PvcWatchEvent(
                    type = event.type,
                    pvc = obj?.toDomainPvc(),
                    resourceVersion = obj?.metadata?.resourceVersion,
                )
            }
        }
    }

    override fun close() {
        watch.close()
    }
}

private fun V1PersistentVolume.toDomainPv(): PersistentVolume? {
    val name = metadata?.name?.trim().orEmpty()
    if (name.isEmpty()) return null

    val claimNamespace = spec?.claimRef?.namespace?.trim().orEmpty()
    val claimName = spec?.claimRef?.name?.trim().orEmpty()
    val claimRef = if (claimName.isBlank()) {
        null
    } else if (claimNamespace.isBlank()) {
        claimName
    } else {
        "$claimNamespace/$claimName"
    }

    return PersistentVolume(
        name = name,
        capacity = spec?.capacity?.get("storage")?.toSuffixedString(),
        accessModes = spec?.accessModes.orEmpty(),
        reclaimPolicy = spec?.persistentVolumeReclaimPolicy,
        status = status?.phase.normalizePvStatus(),
        storageClass = spec?.storageClassName,
        claimRef = claimRef,
    )
}

private fun V1PersistentVolumeClaim.toDomainPvc(): PersistentVolumeClaim? {
    val name = metadata?.name?.trim().orEmpty()
    val namespace = metadata?.namespace?.trim().orEmpty()
    if (name.isEmpty() || namespace.isEmpty()) return null

    return PersistentVolumeClaim(
        name = name,
        namespace = namespace,
        status = status?.phase,
        capacity = status?.capacity?.get("storage")?.toSuffixedString()
            ?: spec?.resources?.requests?.get("storage")?.toSuffixedString(),
        accessModes = status?.accessModes ?: spec?.accessModes.orEmpty(),
        storageClass = spec?.storageClassName,
        volumeName = spec?.volumeName?.trim().orEmpty().ifEmpty { null },
    )
}

private fun V1StorageClass.toDomainStorageClass(): StorageClass? {
    val name = metadata?.name?.trim().orEmpty()
    if (name.isEmpty()) return null

    return StorageClass(
        name = name,
        provisioner = provisioner,
        reclaimPolicy = reclaimPolicy,
        volumeBindingMode = volumeBindingMode,
        allowVolumeExpansion = allowVolumeExpansion,
    )
}

private fun PersistentVolumeClaim.matchVolume(pvStatusByName: Map<String, String?>): PersistentVolumeClaim {
    val pvStatus = volumeName?.let { pvStatusByName[it] }
    val hasHealthyBoundVolume = !volumeName.isNullOrBlank() && pvStatus != null && !pvStatus.equals(PV_STATUS_FAILED, ignoreCase = true)
    val normalizedVolumeName = volumeName?.takeIf { hasHealthyBoundVolume }
    val normalizedStatus = when {
        status.equals(PVC_STATUS_PENDING, ignoreCase = true) -> PVC_STATUS_PENDING
        !hasHealthyBoundVolume -> PVC_STATUS_PENDING
        else -> status
    }

    return copy(
        status = normalizedStatus,
        volumeName = normalizedVolumeName,
    )
}

private fun Map<String, PersistentVolumeClaim>.sortedPvcs(): List<PersistentVolumeClaim> {
    return values
        .sortedWith(
            compareBy<PersistentVolumeClaim>({ it.namespace }, { it.name }),
        )
}

private fun PersistentVolumeClaim.namespacedKey(): String = "${namespace.trim()}/${name.trim()}"

private fun String.requireNamespace(): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "Namespace must not be empty" }
    return normalized
}

private fun buildPvApiClient(kubeConfigPath: Path): ApiClient {
    require(Files.exists(kubeConfigPath)) { "Kubeconfig file not found at $kubeConfigPath" }
    val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    val kubeConfig = KubeConfig.loadKubeConfig(StringReader(raw))
    return ClientBuilder.kubeconfig(kubeConfig)
        .build()
        .setLenientOnJson(true)
}

private const val WATCH_TIMEOUT_SECONDS = 30
private const val PVC_STATUS_PENDING = "Pending"
private const val PV_STATUS_FAILED = "Failed"

private fun String?.normalizePvStatus(): String? {
    return when {
        this.equals(PV_STATUS_FAILED, ignoreCase = true) -> PV_STATUS_FAILED
        else -> this
    }
}

private fun PersistentVolume.normalize(): PersistentVolume = copy(
    status = status.normalizePvStatus(),
)

private val V1_PVC_WATCH_TYPE =
    object : com.google.gson.reflect.TypeToken<Watch.Response<V1PersistentVolumeClaim>>() {}.type
