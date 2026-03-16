package com.kubedroid.core.network.config

import com.kubedroid.core.network.namespace.isAllNamespacesSelection
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Secret
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Watch
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.logging.Logger
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
 * Kubernetes API-backed [SecretRepository] implementation.
 *
 * Secret values are never persisted and are only decoded transiently inside [getDecryptedValue].
 */
class SecretRepositoryImpl(
    private val apiClientProvider: () -> ApiClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchReconnectInitialBackoffMillis: Long = 1_000L,
    private val watchReconnectMaxBackoffMillis: Long = 30_000L,
    private val secretApiFactory: SecretApiFactory = SecretApiFactory.Default,
    private val secretLog: (String) -> Unit = secretLogger::info,
) : SecretRepository {

    constructor(
        kubeConfigPath: Path,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        watchReconnectInitialBackoffMillis: Long = 1_000L,
        watchReconnectMaxBackoffMillis: Long = 30_000L,
        secretApiFactory: SecretApiFactory = SecretApiFactory.Default,
        secretLog: (String) -> Unit = secretLogger::info,
    ) : this(
        apiClientProvider = { buildSecretApiClient(kubeConfigPath) },
        ioDispatcher = ioDispatcher,
        watchReconnectInitialBackoffMillis = watchReconnectInitialBackoffMillis,
        watchReconnectMaxBackoffMillis = watchReconnectMaxBackoffMillis,
        secretApiFactory = secretApiFactory,
        secretLog = secretLog,
    )

    override suspend fun list(namespace: String): Result<List<Secret>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = namespace.trim().ifEmpty {
                throw IllegalArgumentException("Namespace must not be empty")
            }
            val secrets = secretApiFactory.create(apiClientProvider).listSecretsSnapshot(normalizedNamespace).secrets
            logSecretNames("Listed secrets", secrets.map { it.name })
            secrets
        }
    }

    override fun watch(namespace: String): Flow<Result<List<Secret>>> = callbackFlow {
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
                val secretApi = secretApiFactory.create(apiClientProvider)

                try {
                    val initial = secretApi.listSecretsSnapshot(normalizedNamespace)
                    resourceVersion = initial.resourceVersion
                    val state = initial.secrets.associateBy { it.namespacedKey() }.toMutableMap()
                    logSecretNames("Watching secrets", state.keys.toList())
                    trySend(Result.success(state.sortedSecrets()))

                    secretApi.openSecretWatch(normalizedNamespace, resourceVersion).use { watchSession ->
                        reconnectDelayMillis = watchReconnectInitialBackoffMillis.coerceAtLeast(1L)

                        for (event in watchSession) {
                            if (!isActive) break
                            resourceVersion = event.resourceVersion ?: resourceVersion

                            when (event.type?.uppercase()) {
                                "ADDED", "MODIFIED" -> {
                                    event.secret?.let {
                                        state[it.namespacedKey()] = it
                                        logSecretName("Secret changed", it.name)
                                    }
                                    trySend(Result.success(state.sortedSecrets()))
                                }

                                "DELETED" -> {
                                    event.secret?.let {
                                        state.remove(it.namespacedKey())
                                        logSecretName("Secret deleted", it.name)
                                    }
                                    trySend(Result.success(state.sortedSecrets()))
                                }

                                "BOOKMARK" -> Unit
                                else -> Unit
                            }
                        }
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

    override suspend fun get(
        name: String,
        namespace: String,
    ): Result<Secret> = withContext(ioDispatcher) {
        runCatching {
            val normalizedNamespace = namespace.requireNamespace()
            val normalizedName = name.requireName("Secret name")
            val secret = secretApiFactory.create(apiClientProvider).readSecret(
                namespace = normalizedNamespace,
                name = normalizedName,
            )
            logSecretName("Loaded secret metadata", secret.name)
            secret
        }
    }

    override suspend fun getDecryptedValue(
        name: String,
        namespace: String,
        key: String,
        biometricToken: BiometricToken,
    ): Result<String> = withContext(ioDispatcher) {
        if (!biometricToken.isValid()) {
            return@withContext Result.failure(SecurityException("Biometric authentication is required"))
        }

        runCatching {
            val normalizedNamespace = namespace.requireNamespace()
            val normalizedName = name.requireName("Secret name")
            val normalizedKey = key.requireName("Secret key")

            logSecretName("Decrypting secret value", normalizedName)
            val encoded = secretApiFactory.create(apiClientProvider).readEncodedValue(
                namespace = normalizedNamespace,
                name = normalizedName,
                key = normalizedKey,
            ) ?: throw NoSuchElementException("Secret value not found")

            // Decode only for the return value; no decoded bytes are cached in repository state.
            encoded.decodeToSecretValue()
        }
    }

    private fun logSecretName(prefix: String, secretName: String) {
        secretLog("$prefix: $secretName")
    }

    private fun logSecretNames(prefix: String, names: List<String>) {
        if (names.isEmpty()) {
            secretLog("$prefix: none")
            return
        }
        secretLog("$prefix: ${names.sorted().joinToString(separator = ",")}")
    }
}

fun interface SecretApiFactory {
    fun create(apiClientProvider: () -> ApiClient): SecretApi

    data object Default : SecretApiFactory {
        override fun create(apiClientProvider: () -> ApiClient): SecretApi = KubernetesSecretApi(apiClientProvider)
    }
}

interface SecretApi {
    fun listSecretsSnapshot(namespace: String): SecretListSnapshot
    fun openSecretWatch(namespace: String, resourceVersion: String?): SecretWatchSession
    fun readSecret(namespace: String, name: String): Secret
    fun readEncodedValue(namespace: String, name: String, key: String): ByteArray?
}

data class SecretListSnapshot(
    val secrets: List<Secret>,
    val resourceVersion: String?,
)

data class SecretWatchEvent(
    val type: String?,
    val secret: Secret?,
    val resourceVersion: String?,
)

interface SecretWatchSession : Iterable<SecretWatchEvent>, AutoCloseable

private class KubernetesSecretApi(
    private val apiClientProvider: () -> ApiClient,
) : SecretApi {

    override fun listSecretsSnapshot(namespace: String): SecretListSnapshot {
        val api = CoreV1Api(apiClientProvider())
        val response = if (namespace.isAllNamespacesSelection()) {
            api.listSecretForAllNamespaces().execute()
        } else {
            api.listNamespacedSecret(namespace).execute()
        }
        val fallbackNamespace = if (namespace.isAllNamespacesSelection()) null else namespace

        return SecretListSnapshot(
            secrets = response.items.orEmpty().mapNotNull { it.toDomainSecret(fallbackNamespace) },
            resourceVersion = response.metadata?.resourceVersion,
        )
    }

    override fun openSecretWatch(namespace: String, resourceVersion: String?): SecretWatchSession {
        val api = CoreV1Api(apiClientProvider())
        val call = if (namespace.isAllNamespacesSelection()) {
            val requestBuilder = api.listSecretForAllNamespaces()
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        } else {
            val requestBuilder = api.listNamespacedSecret(namespace)
                .watch(true)
                .timeoutSeconds(WATCH_TIMEOUT_SECONDS)
            if (!resourceVersion.isNullOrBlank()) {
                requestBuilder.resourceVersion(resourceVersion)
            }
            requestBuilder.buildCall(null)
        }
        val watch: Watch<V1Secret> = Watch.createWatch(
            apiClientProvider(),
            call,
            V1_SECRET_WATCH_TYPE,
        )
        val fallbackNamespace = if (namespace.isAllNamespacesSelection()) null else namespace
        return KubernetesSecretWatchSession(watch, fallbackNamespace)
    }

    override fun readSecret(namespace: String, name: String): Secret {
        val secret = CoreV1Api(apiClientProvider())
            .readNamespacedSecret(name, namespace)
            .execute()

        return secret.toDomainSecret(namespace)
            ?: throw IllegalStateException("Secret payload is missing required metadata")
    }

    override fun readEncodedValue(namespace: String, name: String, key: String): ByteArray? {
        val secret = CoreV1Api(apiClientProvider())
            .readNamespacedSecret(name, namespace)
            .execute()

        return secret.data?.get(key)?.copyOf()
            ?: secret.stringData?.get(key)?.toByteArray(Charsets.UTF_8)
    }
}

private class KubernetesSecretWatchSession(
    private val watch: Watch<V1Secret>,
    private val namespace: String?,
) : SecretWatchSession {

    override fun iterator(): Iterator<SecretWatchEvent> {
        val delegate = watch.iterator()
        return object : Iterator<SecretWatchEvent> {
            override fun hasNext(): Boolean = delegate.hasNext()

            override fun next(): SecretWatchEvent {
                val event = delegate.next()
                val secret = event.`object`
                return SecretWatchEvent(
                    type = event.type,
                    secret = secret?.toDomainSecret(namespace),
                    resourceVersion = secret?.metadata?.resourceVersion,
                )
            }
        }
    }

    override fun close() {
        watch.close()
    }
}

private fun V1Secret.toDomainSecret(fallbackNamespace: String? = null): Secret? {
    val normalizedName = metadata?.name?.trim().orEmpty()
    if (normalizedName.isEmpty()) return null

    val normalizedNamespace = metadata?.namespace?.trim().orEmpty().ifEmpty {
        fallbackNamespace.orEmpty()
    }
    if (normalizedNamespace.isEmpty()) return null

    val keys = linkedSetOf<String>().apply {
        addAll(data.orEmpty().keys)
        addAll(stringData.orEmpty().keys)
    }.toList().sorted()

    return Secret(
        name = normalizedName,
        namespace = normalizedNamespace,
        type = type.orEmpty(),
        dataKeys = keys,
    )
}

private fun Map<String, Secret>.sortedSecrets(): List<Secret> = values.sortedBy { it.name }

private fun Secret.namespacedKey(): String = "${namespace.trim()}/${name.trim()}"

private fun String.requireNamespace(): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "Namespace must not be empty" }
    return normalized
}

private fun String.requireName(field: String): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "$field must not be empty" }
    return normalized
}

private fun buildSecretApiClient(kubeConfigPath: Path): ApiClient {
    require(Files.exists(kubeConfigPath)) { "Kubeconfig file not found at $kubeConfigPath" }
    val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    val kubeConfig = KubeConfig.loadKubeConfig(StringReader(raw))
    return ClientBuilder.kubeconfig(kubeConfig)
        .build()
        .setLenientOnJson(true)
}

private const val WATCH_TIMEOUT_SECONDS = 30
private val secretLogger: Logger = Logger.getLogger(SecretRepositoryImpl::class.java.name)
private val V1_SECRET_WATCH_TYPE = object : com.google.gson.reflect.TypeToken<Watch.Response<V1Secret>>() {}.type

private fun ByteArray.decodeToSecretValue(): String {
    val rawText = toString(Charsets.UTF_8)
    val normalized = rawText.trim()
    if (!normalized.isLikelyBase64()) {
        return rawText
    }

    val decoded = runCatching { Base64.getMimeDecoder().decode(normalized) }.getOrNull()
        ?: return rawText
    return decoded.toString(Charsets.UTF_8)
}

private fun String.isLikelyBase64(): Boolean {
    if (isEmpty()) return false
    val compact = filterNot(Char::isWhitespace)
    if (compact.length % 4 != 0) return false
    return compact.all { character ->
        character.isLetterOrDigit() || character == '+' || character == '/' || character == '='
    }
}
