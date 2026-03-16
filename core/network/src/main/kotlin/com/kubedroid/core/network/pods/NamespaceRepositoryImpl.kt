package com.kubedroid.core.network.pods

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kubernetes API-backed [NamespaceRepository] implementation.
 */
class NamespaceRepositoryImpl(
    private val apiClientProvider: () -> ApiClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val namespaceApiFactory: NamespaceApiFactory = NamespaceApiFactory.Default,
) : NamespaceRepository {

    constructor(
        kubeConfigPath: Path,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        namespaceApiFactory: NamespaceApiFactory = NamespaceApiFactory.Default,
    ) : this(
        apiClientProvider = { buildNamespaceApiClient(kubeConfigPath) },
        ioDispatcher = ioDispatcher,
        namespaceApiFactory = namespaceApiFactory,
    )

    override suspend fun listNamespaces(): Result<List<String>> = withContext(ioDispatcher) {
        runCatching {
            namespaceApiFactory
                .create(apiClientProvider)
                .listNamespaces()
                .filter { it.isNotBlank() }
                .sorted()
        }.mapFailure { throwable ->
            when (throwable) {
                is ApiException -> when (throwable.code) {
                    403 -> NamespaceForbiddenException()
                    else -> NamespaceApiException("Namespace API returned HTTP ${throwable.code}", throwable)
                }
                else -> NamespaceApiException(throwable.message, throwable)
            }
        }
    }
}

fun interface NamespaceApiFactory {
    fun create(apiClientProvider: () -> ApiClient): NamespaceApi

    data object Default : NamespaceApiFactory {
        override fun create(apiClientProvider: () -> ApiClient): NamespaceApi = KubernetesNamespaceApi(apiClientProvider)
    }
}

interface NamespaceApi {
    fun listNamespaces(): List<String>
}

private class KubernetesNamespaceApi(
    private val apiClientProvider: () -> ApiClient,
) : NamespaceApi {

    override fun listNamespaces(): List<String> {
        val response = CoreV1Api(apiClientProvider())
            .listNamespace()
            .execute()

        return response.items
            .orEmpty()
            .mapNotNull { it.metadata?.name }
    }
}

private fun buildNamespaceApiClient(kubeConfigPath: Path): ApiClient {
    if (!Files.exists(kubeConfigPath)) {
        throw NamespaceApiException("Kubeconfig file not found at $kubeConfigPath")
    }
    val raw = Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    val kubeConfig = try {
        KubeConfig.loadKubeConfig(StringReader(raw))
    } catch (throwable: Throwable) {
        throw NamespaceApiException("Invalid kubeconfig content", throwable)
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

class NamespaceForbiddenException : Exception("Forbidden to list namespaces")

class NamespaceApiException(
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause)
