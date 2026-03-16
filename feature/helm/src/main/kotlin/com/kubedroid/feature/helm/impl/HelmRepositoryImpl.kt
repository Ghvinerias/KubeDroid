package com.kubedroid.feature.helm.impl

import android.content.Context
import com.kubedroid.feature.helm.HelmRepository
import com.kubedroid.feature.helm.model.HelmRelease
import com.kubedroid.feature.helm.model.HelmRevision
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Secret
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class HelmRepositoryImpl : HelmRepository {
    private val apiClientProvider: () -> ApiClient
    private val ioDispatcher: CoroutineDispatcher
    private val secretApiFactory: HelmSecretApiFactory

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        apiClientProvider = {
            buildHelmApiClient(
                FilePaths.kubeConfigPath(context),
            )
        },
        ioDispatcher = Dispatchers.IO,
        secretApiFactory = HelmSecretApiFactory.Default,
    )

    internal constructor(
        apiClientProvider: () -> ApiClient,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        secretApiFactory: HelmSecretApiFactory = HelmSecretApiFactory.Default,
    ) {
        this.apiClientProvider = apiClientProvider
        this.ioDispatcher = ioDispatcher
        this.secretApiFactory = secretApiFactory
    }

    override suspend fun listReleases(namespace: String): Result<List<HelmRelease>> = withContext(ioDispatcher) {
        runCatching {
            val secrets = loadSecrets(namespace = namespace, labelSelector = DEPLOYED_LABEL_SELECTOR)

            secrets.map { secret ->
                val parsed = decodeSecret(secret)
                HelmRelease(
                    name = parsed.name,
                    namespace = parsed.namespace,
                    chart = parsed.chartName,
                    chartVersion = parsed.chartVersion,
                    appVersion = parsed.appVersion,
                    status = parsed.status,
                    lastDeployed = parsed.updatedAt,
                )
            }.sortedBy { it.name }
        }.mapFailure(::mapError)
    }

    override suspend fun getReleaseHistory(
        name: String,
        namespace: String,
    ): Result<List<HelmRevision>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedName = name.requireReleaseName()
            val normalizedNamespace = namespace.requireNamespace()
            val selector = "owner=helm,name=$normalizedName"
            val secrets = loadSecrets(normalizedNamespace, selector)
            if (secrets.isEmpty()) {
                throw NoHelmReleasesException(normalizedNamespace)
            }

            secrets.map { secret ->
                val parsed = decodeSecret(secret)
                HelmRevision(
                    revision = parsed.revision,
                    chart = "${parsed.chartName}-${parsed.chartVersion}",
                    status = parsed.status,
                    updatedAt = parsed.updatedAt,
                    description = parsed.description,
                )
            }.sortedByDescending { it.revision }
        }.mapFailure(::mapError)
    }

    override suspend fun rollbackRelease(
        name: String,
        namespace: String,
        revision: Int,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val normalizedName = name.requireReleaseName()
            val normalizedNamespace = namespace.requireNamespace()
            val sourceSecrets = loadSecrets(normalizedNamespace, "owner=helm,name=$normalizedName")
            if (sourceSecrets.isEmpty()) {
                throw NoHelmReleasesException(normalizedNamespace)
            }

            val parsedBySecret = sourceSecrets
                .map { secret -> secret to decodeSecret(secret) }
                .sortedBy { (_, parsed) -> parsed.revision }

            val availableRevisions = parsedBySecret.map { (_, parsed) -> parsed.revision }
            val target = parsedBySecret.firstOrNull { (_, parsed) -> parsed.revision == revision }
                ?: throw InvalidRollbackRevisionException(
                    releaseName = normalizedName,
                    requestedRevision = revision,
                    availableRevisions = availableRevisions,
                )

            var attempt = 0
            while (true) {
                attempt += 1
                val currentSecrets = loadSecrets(normalizedNamespace, "owner=helm,name=$normalizedName")
                val currentMaxRevision = currentSecrets
                    .map(::decodeSecret)
                    .maxOfOrNull { it.revision } ?: 0
                val nextRevision = currentMaxRevision + 1
                val rollbackReleaseField = HelmReleaseSecretCodec.encodeRollback(
                    secretName = target.first.metadata?.name.orEmpty().ifBlank { UNKNOWN_SECRET_NAME },
                    source = target.second,
                    nextRevision = nextRevision,
                    rollbackTargetRevision = revision,
                    rollbackTimestampMillis = System.currentTimeMillis(),
                )
                val rollbackSecret = buildRollbackSecret(
                    source = target.first,
                    releaseName = normalizedName,
                    namespace = normalizedNamespace,
                    nextRevision = nextRevision,
                    rollbackReleaseField = rollbackReleaseField,
                )
                try {
                    secretApiFactory.create(apiClientProvider).createSecret(normalizedNamespace, rollbackSecret)
                    break
                } catch (apiException: ApiException) {
                    if (apiException.code == 409 && attempt < ROLLBACK_MAX_CREATE_ATTEMPTS) {
                        continue
                    }
                    throw apiException
                }
            }
            Unit
        }.mapFailure(::mapError)
    }

    override suspend fun getReleaseManifest(
        name: String,
        namespace: String,
    ): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val normalizedName = name.requireReleaseName()
            val normalizedNamespace = namespace.requireNamespace()
            val secrets = loadSecrets(normalizedNamespace, "owner=helm,name=$normalizedName")
            if (secrets.isEmpty()) {
                throw NoHelmReleasesException(normalizedNamespace)
            }

            val latest = secrets
                .map(::decodeSecret)
                .maxByOrNull { it.revision }
                ?: throw NoHelmReleasesException(normalizedNamespace)

            latest.manifest
        }.mapFailure(::mapError)
    }

    private fun loadSecrets(namespace: String, labelSelector: String): List<V1Secret> {
        val normalizedNamespace = namespace.requireNamespace()
        return secretApiFactory.create(apiClientProvider).listSecrets(normalizedNamespace, labelSelector)
    }

    private fun decodeSecret(secret: V1Secret): DecodedHelmRelease {
        val metadataName = secret.metadata?.name.orEmpty()
        val rawReleaseField = secret.data?.get(RELEASE_DATA_KEY)
            ?: throw ReleaseDecodeException(
                secretName = metadataName.ifBlank { UNKNOWN_SECRET_NAME },
                reason = "Missing '$RELEASE_DATA_KEY' field",
            )

        return HelmReleaseSecretCodec.decode(
            secretName = metadataName.ifBlank { UNKNOWN_SECRET_NAME },
            rawReleaseField = rawReleaseField,
        )
    }

    private fun buildRollbackSecret(
        source: V1Secret,
        releaseName: String,
        namespace: String,
        nextRevision: Int,
        rollbackReleaseField: ByteArray,
    ): V1Secret {
        val sourceMetadata = source.metadata
        val sourceName = sourceMetadata?.name.orEmpty()
        val generatedName = sourceName.replace(REVISION_SUFFIX_REGEX, ".v$nextRevision")
            .takeIf { it.isNotBlank() }
            ?: "sh.helm.release.v1.$releaseName.v$nextRevision"

        val labels = linkedMapOf<String, String>().apply {
            putAll(sourceMetadata?.labels.orEmpty())
            put("owner", "helm")
            put("name", releaseName)
            put("status", "deployed")
            put("version", nextRevision.toString())
        }

        val annotations = linkedMapOf<String, String>().apply {
            putAll(sourceMetadata?.annotations.orEmpty())
            put("meta.helm.sh/release-name", releaseName)
            put("meta.helm.sh/release-namespace", namespace)
        }

        return V1Secret().apply {
            metadata = V1ObjectMeta().apply {
                this.name = generatedName
                this.namespace = namespace
                this.labels = labels
                this.annotations = annotations
            }
            type = source.type ?: HELM_SECRET_TYPE
            data = source.data.orEmpty().toMutableMap().apply {
                this[RELEASE_DATA_KEY] = rollbackReleaseField
            }
        }
    }

    private fun mapError(throwable: Throwable): Throwable {
        return when (throwable) {
            is NoHelmReleasesException -> throwable
            is ReleaseDecodeException -> throwable
            is InvalidRollbackRevisionException -> throwable
            is EmptyNamespaceException -> throwable
            is EmptyReleaseNameException -> throwable
            is ApiException -> HelmRepositoryException(
                message = "Helm secret API error HTTP ${throwable.code}",
                cause = throwable,
            )
            else -> HelmRepositoryException(
                message = throwable.message ?: "Failed to execute Helm operation",
                cause = throwable,
            )
        }
    }

    private object FilePaths {
        fun kubeConfigPath(context: Context): Path = context.filesDir.toPath().resolve("kube/config")
    }

    companion object {
        private const val DEPLOYED_LABEL_SELECTOR = "owner=helm"
        private const val RELEASE_DATA_KEY = "release"
        private const val HELM_SECRET_TYPE = "helm.sh/release.v1"
        private const val UNKNOWN_SECRET_NAME = "<unknown-secret>"
        private const val ROLLBACK_MAX_CREATE_ATTEMPTS = 3
        private val REVISION_SUFFIX_REGEX = Regex("\\.v\\d+$")
    }
}

internal object HelmReleaseSecretCodec {
    fun decode(
        secretName: String,
        rawReleaseField: ByteArray,
    ): DecodedHelmRelease {
        val gzipPayload = decodeBase64(secretName, rawReleaseField)
        val protobufPayload = unzip(secretName, gzipPayload)
        return decodeReleaseMessage(secretName, protobufPayload)
    }

    fun encodeRollback(
        secretName: String,
        source: DecodedHelmRelease,
        nextRevision: Int,
        rollbackTargetRevision: Int,
        rollbackTimestampMillis: Long,
    ): ByteArray {
        return try {
            val rollbackPayload = protoMessage {
                stringField(1, source.name)
                messageField(2) {
                    messageField(2) {
                        int64Field(1, rollbackTimestampMillis / 1_000L)
                        int32Field(2, ((rollbackTimestampMillis % 1_000L) * 1_000_000L).toInt())
                    }
                    stringField(4, "Rollback to revision $rollbackTargetRevision")
                    int32Field(5, HELM_STATUS_DEPLOYED)
                }
                messageField(3) {
                    messageField(1) {
                        stringField(1, source.chartName)
                        stringField(4, source.chartVersion)
                        stringField(16, source.appVersion)
                    }
                }
                stringField(4, source.manifest)
                int32Field(6, nextRevision)
                stringField(7, source.namespace)
            }

            val compressed = gzip(rollbackPayload)
            Base64.getEncoder().encodeToString(compressed).toByteArray(Charsets.UTF_8)
        } catch (throwable: Throwable) {
            throw ReleaseDecodeException(
                secretName = secretName,
                reason = "Failed to encode rollback release payload",
                cause = throwable,
            )
        }
    }

    private fun decodeBase64(secretName: String, rawReleaseField: ByteArray): ByteArray {
        val text = rawReleaseField.toString(Charsets.UTF_8).trim()
        return try {
            Base64.getDecoder().decode(text)
        } catch (throwable: Throwable) {
            throw ReleaseDecodeException(
                secretName = secretName,
                reason = "release field is not valid base64",
                cause = throwable,
            )
        }
    }

    private fun unzip(secretName: String, payload: ByteArray): ByteArray {
        return try {
            GZIPInputStream(ByteArrayInputStream(payload)).use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var totalRead = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    totalRead += read
                    if (totalRead > MAX_DECOMPRESSED_RELEASE_BYTES) {
                        throw ReleaseDecodeException(
                            secretName = secretName,
                            reason = "release payload exceeds max decompressed size",
                        )
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (throwable: Throwable) {
            if (throwable is ReleaseDecodeException) {
                throw throwable
            }
            throw ReleaseDecodeException(
                secretName = secretName,
                reason = "release field is not valid gzip data",
                cause = throwable,
            )
        }
    }

    private fun gzip(payload: ByteArray): ByteArray {
        return java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(payload) }
            out.toByteArray()
        }
    }

    private fun decodeReleaseMessage(secretName: String, payload: ByteArray): DecodedHelmRelease {
        try {
            val releaseReader = ProtoReader(payload)
            var name = ""
            var namespace = ""
            var revision = 0
            var manifest = ""
            var chartName = ""
            var chartVersion = ""
            var appVersion = ""
            var status = "unknown"
            var updatedAt: Long? = null
            var description = ""

            while (!releaseReader.isEof()) {
                val tag = releaseReader.readTag()
                val field = tag ushr 3
                when (field) {
                    1 -> name = releaseReader.readString()
                    2 -> {
                        val infoReader = ProtoReader(releaseReader.readLengthDelimited())
                        while (!infoReader.isEof()) {
                            val infoTag = infoReader.readTag()
                            val infoField = infoTag ushr 3
                            when (infoField) {
                                2 -> updatedAt = parseTimestamp(infoReader.readLengthDelimited())
                                4 -> description = infoReader.readString()
                                5 -> status = helmStatus(infoReader.readVarint32())
                                else -> infoReader.skipField(infoTag)
                            }
                        }
                    }
                    3 -> {
                        val chartReader = ProtoReader(releaseReader.readLengthDelimited())
                        while (!chartReader.isEof()) {
                            val chartTag = chartReader.readTag()
                            val chartField = chartTag ushr 3
                            if (chartField == 1) {
                                val metadataReader = ProtoReader(chartReader.readLengthDelimited())
                                while (!metadataReader.isEof()) {
                                    val metadataTag = metadataReader.readTag()
                                    val metadataField = metadataTag ushr 3
                                    when (metadataField) {
                                        1 -> chartName = metadataReader.readString()
                                        4 -> chartVersion = metadataReader.readString()
                                        16 -> appVersion = metadataReader.readString()
                                        else -> metadataReader.skipField(metadataTag)
                                    }
                                }
                            } else {
                                chartReader.skipField(chartTag)
                            }
                        }
                    }
                    4 -> manifest = releaseReader.readString()
                    6 -> revision = releaseReader.readVarint32()
                    7 -> namespace = releaseReader.readString()
                    else -> releaseReader.skipField(tag)
                }
            }

            if (name.isBlank()) {
                throw ReleaseDecodeException(secretName, "Decoded release payload is missing release name")
            }
            if (namespace.isBlank()) {
                throw ReleaseDecodeException(secretName, "Decoded release payload is missing namespace")
            }
            if (revision <= 0) {
                throw ReleaseDecodeException(secretName, "Decoded release payload has invalid revision")
            }

            return DecodedHelmRelease(
                name = name,
                namespace = namespace,
                revision = revision,
                manifest = manifest,
                chartName = chartName,
                chartVersion = chartVersion,
                appVersion = appVersion,
                status = status,
                updatedAt = updatedAt,
                description = description,
            )
        } catch (throwable: Throwable) {
            if (throwable is ReleaseDecodeException) {
                throw throwable
            }
            throw ReleaseDecodeException(
                secretName = secretName,
                reason = "Failed to parse Helm release protobuf payload",
                cause = throwable,
            )
        }
    }

    private fun parseTimestamp(payload: ByteArray): Long? {
        val timestampReader = ProtoReader(payload)
        var seconds = 0L
        var nanos = 0
        while (!timestampReader.isEof()) {
            val timestampTag = timestampReader.readTag()
            val timestampField = timestampTag ushr 3
            when (timestampField) {
                1 -> seconds = timestampReader.readVarint64()
                2 -> nanos = timestampReader.readVarint32()
                else -> timestampReader.skipField(timestampTag)
            }
        }

        return runCatching {
            Instant.ofEpochSecond(seconds, nanos.toLong()).toEpochMilli()
        }.getOrNull()
    }

    private fun helmStatus(value: Int): String {
        return when (value) {
            1 -> "deployed"
            2 -> "uninstalled"
            3 -> "superseded"
            4 -> "failed"
            5 -> "uninstalling"
            6 -> "pending_install"
            7 -> "pending_upgrade"
            8 -> "pending_rollback"
            else -> "unknown"
        }
    }

    private fun protoMessage(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).toByteArray()

    private const val HELM_STATUS_DEPLOYED = 1
    private const val MAX_DECOMPRESSED_RELEASE_BYTES = 5 * 1024 * 1024
}

internal data class DecodedHelmRelease(
    val name: String,
    val namespace: String,
    val revision: Int,
    val manifest: String,
    val chartName: String,
    val chartVersion: String,
    val appVersion: String,
    val status: String,
    val updatedAt: Long?,
    val description: String,
)

internal class ProtoReader(
    private val bytes: ByteArray,
) {
    private var position: Int = 0

    fun isEof(): Boolean = position >= bytes.size

    fun readTag(): Int {
        val tag = readVarint32()
        if (tag == 0) {
            throw EOFException("Unexpected tag 0")
        }
        return tag
    }

    fun readVarint32(): Int = readVarint64().toInt()

    fun readVarint64(): Long {
        var shift = 0
        var result = 0L
        while (shift < 64) {
            val current = readByte().toInt() and 0xFF
            result = result or ((current and 0x7F).toLong() shl shift)
            if ((current and 0x80) == 0) {
                return result
            }
            shift += 7
        }
        throw EOFException("Malformed varint")
    }

    fun readString(): String = readLengthDelimited().toString(Charsets.UTF_8)

    fun readLengthDelimited(): ByteArray {
        val length = readVarint32()
        if (length < 0 || position + length > bytes.size) {
            throw EOFException("Malformed length-delimited field")
        }
        val value = bytes.copyOfRange(position, position + length)
        position += length
        return value
    }

    fun skipField(tag: Int) {
        when (tag and 0x7) {
            0 -> readVarint64()
            1 -> skip(8)
            2 -> skip(readVarint32())
            5 -> skip(4)
            else -> throw EOFException("Unsupported protobuf wire type ${tag and 0x7}")
        }
    }

    private fun skip(length: Int) {
        if (length < 0 || position + length > bytes.size) {
            throw EOFException("Unexpected EOF while skipping")
        }
        position += length
    }

    private fun readByte(): Byte {
        if (position >= bytes.size) {
            throw EOFException("Unexpected EOF")
        }
        return bytes[position++]
    }
}

internal class ProtoWriter {
    private val out = java.io.ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun int32Field(field: Int, value: Int) {
        writeTag(field, 0)
        writeVarint(value.toLong())
    }

    fun int64Field(field: Int, value: Long) {
        writeTag(field, 0)
        writeVarint(value)
    }

    fun stringField(field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(field, 2)
        writeVarint(bytes.size.toLong())
        out.write(bytes)
    }

    fun messageField(field: Int, block: ProtoWriter.() -> Unit) {
        val nested = ProtoWriter().apply(block).toByteArray()
        writeTag(field, 2)
        writeVarint(nested.size.toLong())
        out.write(nested)
    }

    private fun writeTag(field: Int, wireType: Int) {
        writeVarint(((field shl 3) or wireType).toLong())
    }

    private fun writeVarint(value: Long) {
        var remaining = value
        while (true) {
            if ((remaining and 0x7FL.inv()) == 0L) {
                out.write(remaining.toInt())
                return
            }
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
    }
}

fun interface HelmSecretApiFactory {
    fun create(apiClientProvider: () -> ApiClient): HelmSecretApi

    data object Default : HelmSecretApiFactory {
        override fun create(apiClientProvider: () -> ApiClient): HelmSecretApi = KubernetesHelmSecretApi(apiClientProvider)
    }
}

interface HelmSecretApi {
    fun listSecrets(namespace: String, labelSelector: String): List<V1Secret>

    fun createSecret(namespace: String, secret: V1Secret): V1Secret
}

private class KubernetesHelmSecretApi(
    private val apiClientProvider: () -> ApiClient,
) : HelmSecretApi {
    override fun listSecrets(namespace: String, labelSelector: String): List<V1Secret> {
        return CoreV1Api(apiClientProvider())
            .listNamespacedSecret(namespace)
            .labelSelector(labelSelector)
            .execute()
            .items
            .orEmpty()
    }

    override fun createSecret(namespace: String, secret: V1Secret): V1Secret {
        return CoreV1Api(apiClientProvider())
            .createNamespacedSecret(namespace, secret)
            .execute()
    }
}

private fun buildHelmApiClient(kubeConfigPath: Path): ApiClient {
    if (!Files.exists(kubeConfigPath)) {
        throw HelmRepositoryException("Kubeconfig file not found")
    }
    val kubeConfig = KubeConfig.loadKubeConfig(
        StringReader(Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)),
    )
    return ClientBuilder.kubeconfig(kubeConfig)
        .build()
        .setLenientOnJson(true)
}

private fun String.requireNamespace(): String {
    val normalized = trim()
    if (normalized.isEmpty()) {
        throw EmptyNamespaceException()
    }
    return normalized
}

private fun String.requireReleaseName(): String {
    val normalized = trim()
    if (normalized.isEmpty()) {
        throw EmptyReleaseNameException()
    }
    return normalized
}

private fun <T> Result<T>.mapFailure(mapper: (Throwable) -> Throwable): Result<T> {
    return fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapper(it)) },
    )
}

class HelmRepositoryException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class NoHelmReleasesException(
    namespace: String,
) : Exception("No Helm releases found in namespace '$namespace'")

class ReleaseDecodeException(
    secretName: String,
    reason: String,
    cause: Throwable? = null,
) : Exception("Failed to decode Helm release from secret '$secretName': $reason", cause)

class InvalidRollbackRevisionException(
    releaseName: String,
    requestedRevision: Int,
    availableRevisions: List<Int>,
) : Exception(
    "Cannot rollback release '$releaseName' to revision $requestedRevision; available revisions: ${availableRevisions.joinToString(",")}",
)

class EmptyNamespaceException : IllegalArgumentException("Namespace cannot be empty")

class EmptyReleaseNameException : IllegalArgumentException("Release name cannot be empty")
