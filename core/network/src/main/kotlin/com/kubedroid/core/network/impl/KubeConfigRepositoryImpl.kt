package com.kubedroid.core.network.impl

import com.kubedroid.core.network.kubeconfig.KubeCluster
import com.kubedroid.core.network.kubeconfig.KubeConfig
import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import com.kubedroid.core.network.kubeconfig.KubeContext
import com.kubedroid.core.network.kubeconfig.KubeUser
import io.kubernetes.client.util.KubeConfig as ClientKubeConfig
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml

/**
 * File-backed [KubeConfigRepository] implementation using kubernetes-client/java parsing.
 */
class KubeConfigRepositoryImpl(
    private val kubeConfigPath: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KubeConfigRepository {

    private val mutex = Mutex()
    private val yaml = Yaml()

    override suspend fun load(): Result<KubeConfig> = withContext(ioDispatcher) {
        mutex.withLock {
            runCatching {
                val raw = readRawKubeConfig()
                validateKubeConfig(raw)
                parseToDomain(raw)
            }.mapFailure(::mapKubeConfigError)
        }
    }

    override suspend fun save(config: KubeConfig): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            runCatching {
                val existingRoot = readExistingRoot()
                val content = serialize(config, existingRoot)
                validateKubeConfig(content)
                writeRawKubeConfig(content)
                Unit
            }.mapFailure(::mapKubeConfigError)
        }
    }

    override suspend fun delete(): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            runCatching {
                Files.deleteIfExists(kubeConfigPath)
                Unit
            }.mapFailure { KubeConfigStorageException(it.message, it) }
        }
    }

    override suspend fun listContexts(): Result<List<KubeContext>> {
        return load().mapCatching { it.contexts }
    }

    override suspend fun setActiveContext(contextName: String): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            runCatching {
                val raw = readRawKubeConfig()
                validateKubeConfig(raw)
                val config = parseToDomain(raw)
                if (config.contexts.none { it.name == contextName }) {
                    throw InvalidKubeConfigException("Context '$contextName' not found")
                }

                val updated = config.copy(currentContext = contextName)
                val existingRoot = parseRoot(raw)
                val serialized = serialize(updated, existingRoot)
                validateKubeConfig(serialized)
                writeRawKubeConfig(serialized)
                Unit
            }.mapFailure(::mapKubeConfigError)
        }
    }

    private fun readRawKubeConfig(): String {
        if (!Files.exists(kubeConfigPath)) {
            throw KubeConfigStorageException("Kubeconfig file not found")
        }
        return Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    }

    private fun writeRawKubeConfig(content: String) {
        kubeConfigPath.parent?.let { Files.createDirectories(it) }
        Files.write(kubeConfigPath, content.toByteArray(Charsets.UTF_8))
    }

    private fun validateKubeConfig(raw: String) {
        try {
            ClientKubeConfig.loadKubeConfig(StringReader(raw))
        } catch (t: Throwable) {
            throw InvalidKubeConfigException("Invalid kubeconfig content", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseToDomain(raw: String): KubeConfig {
        val root = parseRoot(raw)

        val contexts = (root["contexts"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { entry ->
            val name = entry["name"] as? String ?: return@mapNotNull null
            val contextMap = entry["context"] as? Map<String, Any?> ?: return@mapNotNull null
            val cluster = contextMap["cluster"] as? String ?: return@mapNotNull null
            val user = contextMap["user"] as? String ?: return@mapNotNull null
            val namespace = contextMap["namespace"] as? String
            KubeContext(name = name, cluster = cluster, user = user, namespace = namespace)
        }

        val clusters = (root["clusters"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { entry ->
            val name = entry["name"] as? String ?: return@mapNotNull null
            val clusterMap = entry["cluster"] as? Map<String, Any?> ?: return@mapNotNull null
            val server = clusterMap["server"] as? String ?: return@mapNotNull null
            KubeCluster(name = name, server = server, raw = clusterMap)
        }

        val users = (root["users"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { entry ->
            val name = entry["name"] as? String ?: return@mapNotNull null
            val userMap = entry["user"] as? Map<String, Any?> ?: emptyMap()
            KubeUser(name = name, raw = userMap)
        }

        return KubeConfig(
            contexts = contexts,
            clusters = clusters,
            users = users,
            currentContext = root["current-context"] as? String,
        )
    }

    private fun serialize(config: KubeConfig, existingRoot: Map<String, Any?>): String {
        val root = LinkedHashMap(existingRoot)
        root["apiVersion"] = root["apiVersion"] ?: "v1"
        root["kind"] = root["kind"] ?: "Config"
        root["contexts"] = config.contexts.map { ctx ->
            linkedMapOf(
                "name" to ctx.name,
                "context" to linkedMapOf(
                    "cluster" to ctx.cluster,
                    "user" to ctx.user,
                    "namespace" to ctx.namespace,
                ).filterValues { it != null },
            )
        }

        val existingClustersByName = existingNamedEntries(existingRoot, "clusters")
        root["clusters"] = config.clusters.map { cluster ->
            val preservedCluster = (existingClustersByName[cluster.name]?.get("cluster") as? Map<String, Any?>).orEmpty()
            val mergedCluster = LinkedHashMap(preservedCluster)
            mergedCluster.putAll(cluster.raw)
            mergedCluster["server"] = cluster.server
            linkedMapOf(
                "name" to cluster.name,
                "cluster" to mergedCluster,
            )
        }

        val existingUsersByName = existingNamedEntries(existingRoot, "users")
        root["users"] = config.users.map { user ->
            val preservedUser = (existingUsersByName[user.name]?.get("user") as? Map<String, Any?>).orEmpty()
            val mergedUser = LinkedHashMap(preservedUser)
            mergedUser.putAll(user.raw)
            linkedMapOf(
                "name" to user.name,
                "user" to mergedUser,
            )
        }

        if (config.currentContext == null) {
            root.remove("current-context")
        } else {
            root["current-context"] = config.currentContext
        }

        return yaml.dump(root)
    }

    private fun readExistingRoot(): Map<String, Any?> {
        if (!Files.exists(kubeConfigPath)) return emptyMap()
        return parseRoot(Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8))
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseRoot(raw: String): Map<String, Any?> {
        return yaml.load<Any?>(raw) as? Map<String, Any?> ?: emptyMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun existingNamedEntries(root: Map<String, Any?>, key: String): Map<String, Map<String, Any?>> {
        val entries = root[key] as? List<Map<String, Any?>> ?: return emptyMap()
        return entries.mapNotNull { entry ->
            val name = entry["name"] as? String ?: return@mapNotNull null
            name to entry
        }.toMap()
    }

    private fun mapKubeConfigError(throwable: Throwable): Throwable {
        return when (throwable) {
            is InvalidKubeConfigException -> throwable
            is KubeConfigStorageException -> throwable
            else -> KubeConfigStorageException(throwable.message, throwable)
        }
    }
}

private fun <T> Result<T>.mapFailure(mapper: (Throwable) -> Throwable): Result<T> {
    return fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapper(it)) },
    )
}

class InvalidKubeConfigException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class KubeConfigStorageException(
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause)
