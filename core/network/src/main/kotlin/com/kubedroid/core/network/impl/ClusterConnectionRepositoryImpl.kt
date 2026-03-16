package com.kubedroid.core.network.impl

import com.kubedroid.core.network.connection.ClusterConnectionRepository
import com.kubedroid.core.network.connection.ClusterConnectionState
import com.kubedroid.core.network.kubeconfig.KubeContext
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import java.io.InterruptedIOException
import java.io.StringReader
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kubernetes-backed [ClusterConnectionRepository] implementation.
 */
class ClusterConnectionRepositoryImpl(
    private val kubeConfigPath: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ClusterConnectionRepository {

    private val state = MutableStateFlow<ClusterConnectionState>(ClusterConnectionState.Disconnected)
    private val mutex = Mutex()
    private var activeClient: ApiClient? = null

    override suspend fun connect(context: KubeContext): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            state.value = ClusterConnectionState.Connecting(context.name)

            runCatching {
                val client = buildClient(context)
                CoreV1Api(client).listNamespace().limit(1).execute()

                activeClient = client
                state.value = ClusterConnectionState.Connected(context.name)
                Unit
            }.mapFailure {
                val mapped = mapConnectionError(it)
                activeClient = null
                state.value = ClusterConnectionState.Failed(context.name, mapped.message)
                mapped
            }
        }
    }

    override suspend fun disconnect(): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            runCatching {
                activeClient = null
                state.value = ClusterConnectionState.Disconnected
                Unit
            }.mapFailure { ClusterDisconnectException(it.message, it) }
        }
    }

    override fun getState(): Flow<ClusterConnectionState> = state.asStateFlow()

    private fun buildClient(context: KubeContext): ApiClient {
        val raw = readRawKubeConfig()

        val kubeConfig = try {
            KubeConfig.loadKubeConfig(StringReader(raw))
        } catch (t: Throwable) {
            throw InvalidKubeConfigException("Invalid kubeconfig content", t)
        }

        kubeConfig.setContext(context.name)

        return ClientBuilder.kubeconfig(kubeConfig)
            .build()
            .setLenientOnJson(true)
    }

    private fun readRawKubeConfig(): String {
        if (!Files.exists(kubeConfigPath)) {
            throw InvalidKubeConfigException("Kubeconfig file not found")
        }
        return Files.readAllBytes(kubeConfigPath).toString(Charsets.UTF_8)
    }

    private fun mapConnectionError(throwable: Throwable): Throwable {
        if (throwable is InvalidKubeConfigException) {
            return throwable
        }

        val errorChain = throwable.causeChain()
        val apiException = errorChain.filterIsInstance<ApiException>().firstOrNull()
        val unreachableCause = errorChain.firstOrNull {
            it is UnknownHostException ||
                it is ConnectException ||
                it is NoRouteToHostException ||
                it is SocketTimeoutException ||
                it is InterruptedIOException
        }
        val tlsHandshake = errorChain.firstOrNull {
            it is SSLHandshakeException || it is SSLPeerUnverifiedException
        }
        val tlsException = errorChain.filterIsInstance<SSLException>().firstOrNull()

        if (unreachableCause != null) {
            return UnreachableApiServerException(unreachableCause.message, throwable)
        }

        if (tlsHandshake != null) {
            return mapTlsHandshakeError(tlsHandshake)
        }

        if (tlsException != null) {
            return if (tlsException.message?.contains("self signed", ignoreCase = true) == true) {
                SelfSignedTlsException("Self-signed TLS certificate is not trusted", throwable)
            } else {
                ClusterConnectionException(tlsException.message, throwable)
            }
        }

        if (apiException != null) {
            return mapApiException(apiException, throwable)
        }

        return when (throwable) {
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            is SocketTimeoutException,
            is InterruptedIOException,
            -> UnreachableApiServerException(throwable.message, throwable)
            is SSLHandshakeException,
            is SSLPeerUnverifiedException,
            -> mapTlsHandshakeError(throwable)
            is SSLException -> {
                if (throwable.message?.contains("self signed", ignoreCase = true) == true) {
                    SelfSignedTlsException("Self-signed TLS certificate is not trusted", throwable)
                } else {
                    ClusterConnectionException(throwable.message, throwable)
                }
            }
            is ApiException -> mapApiException(throwable, throwable)
            else -> ClusterConnectionException(throwable.message, throwable)
        }
    }

    private fun mapApiException(apiException: ApiException, cause: Throwable): Throwable {
        val body = apiException.responseBody.orEmpty()
        val expiredToken = apiException.code == 401 ||
            (apiException.code == 403 && body.contains("expired", ignoreCase = true))

        return if (expiredToken) {
            ExpiredTokenException("Expired or invalid token", cause)
        } else {
            ClusterConnectionException("Kubernetes API error: ${apiException.code}", cause)
        }
    }

    private fun mapTlsHandshakeError(throwable: Throwable): Throwable {
        val message = throwable.message.orEmpty()

        return when {
            message.contains("self signed", ignoreCase = true) ||
                message.contains("unknown ca", ignoreCase = true) -> {
                SelfSignedTlsException("Self-signed TLS certificate is not trusted", throwable)
            }
            message.contains("bad_certificate", ignoreCase = true) ||
                message.contains("certificate required", ignoreCase = true) ||
                message.contains("handshake_failure", ignoreCase = true) -> {
                ClientCertificateAuthException("Client certificate authentication failed", throwable)
            }
            else -> ClusterConnectionException(message, throwable)
        }
    }
}

private fun <T> Result<T>.mapFailure(mapper: (Throwable) -> Throwable): Result<T> {
    return fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapper(it)) },
    )
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeChain
    while (current != null) {
        yield(current)
        current = current.cause
    }
}

class ClusterConnectionException(
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause)

class UnreachableApiServerException(
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause)

class ExpiredTokenException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class SelfSignedTlsException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class ClientCertificateAuthException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class ClusterDisconnectException(
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause)
