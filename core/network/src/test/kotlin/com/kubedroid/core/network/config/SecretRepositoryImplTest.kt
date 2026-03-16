package com.kubedroid.core.network.config

import io.kubernetes.client.openapi.ApiClient
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SecretRepositoryImplTest {

    @Test
    fun test_list_and_get_returnMetadataOnly() = runTest {
        val fakeApi = FakeSecretApi(
            listSnapshot = SecretListSnapshot(
                secrets = listOf(
                    Secret(
                        name = "db-secret",
                        namespace = "default",
                        type = "Opaque",
                        dataKeys = listOf("username", "password"),
                    ),
                ),
                resourceVersion = "2",
            ),
            getResult = Secret(
                name = "db-secret",
                namespace = "default",
                type = "Opaque",
                dataKeys = listOf("username", "password"),
            ),
        )

        val repository = SecretRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            secretApiFactory = SecretApiFactory { fakeApi },
            secretLog = {},
        )

        val listResult = repository.list("default")
        val getResult = repository.get("db-secret", "default")

        assertTrue(listResult.isSuccess)
        assertTrue(getResult.isSuccess)
        assertEquals(listOf("username", "password"), listResult.getOrThrow().single().dataKeys)
        assertEquals(listOf("username", "password"), getResult.getOrThrow().dataKeys)
        assertEquals(0, fakeApi.readEncodedValueCalls)
        assertEquals(1, fakeApi.listCalls)
        assertEquals(1, fakeApi.readSecretCalls)
    }

    @Test
    fun test_watch_metadataOnly_neverReadsEncodedValues() = runTest {
        val fakeApi = FakeSecretApi(
            listOutcomes = ArrayDeque(
                listOf(
                    SecretListSnapshot(
                        secrets = listOf(
                            Secret("alpha", "default", "Opaque", listOf("username", "password")),
                        ),
                        resourceVersion = "1",
                    ),
                ),
            ),
            watchOutcomes = ArrayDeque(
                listOf(
                    FakeSecretWatchSession(
                        listOf(
                            SecretWatchEvent(
                                type = "ADDED",
                                secret = Secret("beta", "default", "Opaque", listOf("token")),
                                resourceVersion = "2",
                            ),
                            SecretWatchEvent(
                                type = "MODIFIED",
                                secret = Secret("alpha", "default", "Opaque", listOf("username")),
                                resourceVersion = "3",
                            ),
                            SecretWatchEvent(
                                type = "DELETED",
                                secret = Secret("beta", "default", "Opaque", listOf("token")),
                                resourceVersion = "4",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val repository = SecretRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            secretApiFactory = SecretApiFactory { fakeApi },
            secretLog = {},
        )

        val emissions = repository.watch("default").take(4).toList()

        assertTrue(emissions.all { it.isSuccess })
        assertEquals(listOf("alpha"), emissions[0].getOrThrow().map { it.name })
        assertEquals(listOf("alpha", "beta"), emissions[1].getOrThrow().map { it.name })
        assertEquals(listOf("username"), emissions[2].getOrThrow().first { it.name == "alpha" }.dataKeys)
        assertEquals(listOf("alpha"), emissions[3].getOrThrow().map { it.name })
        assertEquals(0, fakeApi.readEncodedValueCalls)
        assertTrue(fakeApi.openWatchCalls >= 1)
    }

    @Test
    fun test_getDecryptedValue_missingKey_returnsFailureWithoutCaching() = runTest {
        val fakeApi = FakeSecretApi(
            listSnapshot = SecretListSnapshot(emptyList(), resourceVersion = "1"),
            encodedValue = null,
        )
        val repository = SecretRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            secretApiFactory = SecretApiFactory { fakeApi },
            secretLog = {},
        )

        val token = BiometricToken.fromAuthProof(proof = "proof")
        val result = repository.getDecryptedValue(
            name = "db-secret",
            namespace = "default",
            key = "password",
            biometricToken = token,
        )

        assertTrue(result.isFailure)
        assertIs<NoSuchElementException>(result.exceptionOrNull())
        assertEquals(1, fakeApi.readEncodedValueCalls)
        assertNull(fakeApi.lastEncodedValueReturned)
    }

    @Test
    fun test_getDecryptedValue_requiresValidBiometricToken() = runTest {
        val fakeApi = FakeSecretApi(
            listSnapshot = SecretListSnapshot(emptyList(), resourceVersion = "1"),
            encodedValue = "super-secret".toByteArray(),
        )
        val repository = SecretRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            secretApiFactory = SecretApiFactory { fakeApi },
            secretLog = {},
        )

        val expiredToken = BiometricToken.fromAuthProof(
            proof = "proof",
            validForMillis = 1L,
            nowEpochMillis = 0L,
        )
        val result = repository.getDecryptedValue(
            name = "db-secret",
            namespace = "default",
            key = "password",
            biometricToken = expiredToken,
        )

        assertTrue(result.isFailure)
        assertEquals(0, fakeApi.readEncodedValueCalls)
        assertIs<SecurityException>(result.exceptionOrNull())
    }

    @Test
    fun test_getDecryptedValue_decodesOnlyWhenAuthorized() = runTest {
        val fakeApi = FakeSecretApi(
            listSnapshot = SecretListSnapshot(emptyList(), resourceVersion = "1"),
            encodedValue = "super-secret".toByteArray(),
        )
        val repository = SecretRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            secretApiFactory = SecretApiFactory { fakeApi },
            secretLog = {},
        )

        val token = BiometricToken.fromAuthProof(proof = "proof")
        val result = repository.getDecryptedValue(
            name = "db-secret",
            namespace = "default",
            key = "password",
            biometricToken = token,
        )

        assertTrue(result.isSuccess)
        assertEquals("super-secret", result.getOrThrow())
        assertEquals(1, fakeApi.readEncodedValueCalls)
        assertEquals("super-secret", fakeApi.lastEncodedValueReturned?.toString(Charsets.UTF_8))
    }

    @Test
    fun test_getDecryptedValue_base64EncodedPayload_isDecodedToUtf8() = runTest {
        val encodedSecret = Base64.getEncoder().encodeToString("db-password".toByteArray(Charsets.UTF_8))
        val fakeApi = FakeSecretApi(
            listSnapshot = SecretListSnapshot(emptyList(), resourceVersion = "1"),
            encodedValue = encodedSecret.toByteArray(Charsets.UTF_8),
        )
        val repository = SecretRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            secretApiFactory = SecretApiFactory { fakeApi },
            secretLog = {},
        )

        val token = BiometricToken.fromAuthProof(proof = "proof")
        val result = repository.getDecryptedValue(
            name = "db-secret",
            namespace = "default",
            key = "password",
            biometricToken = token,
        )

        assertTrue(result.isSuccess)
        assertEquals("db-password", result.getOrThrow())
    }
}

private class FakeSecretApi(
    private val listSnapshot: SecretListSnapshot = SecretListSnapshot(
        secrets = emptyList(),
        resourceVersion = "1",
    ),
    private val getResult: Secret = Secret(
        name = "db-secret",
        namespace = "default",
        type = "Opaque",
        dataKeys = listOf("password"),
    ),
    private val listOutcomes: ArrayDeque<Any> = ArrayDeque(),
    private val watchOutcomes: ArrayDeque<Any> = ArrayDeque(),
    private val encodedValue: ByteArray? = null,
) : SecretApi {
    var listCalls: Int = 0
    var readSecretCalls: Int = 0
    var openWatchCalls: Int = 0
    var readEncodedValueCalls: Int = 0
    var lastEncodedValueReturned: ByteArray? = null

    override fun listSecretsSnapshot(namespace: String): SecretListSnapshot {
        listCalls += 1
        val outcome = listOutcomes.removeFirstOrNull() ?: listSnapshot
        return when (outcome) {
            is SecretListSnapshot -> outcome
            is Throwable -> throw outcome
            else -> throw IllegalStateException("Unsupported list outcome: ${outcome::class.simpleName}")
        }
    }

    override fun openSecretWatch(namespace: String, resourceVersion: String?): SecretWatchSession {
        openWatchCalls += 1
        val outcome = watchOutcomes.removeFirstOrNull()
            ?: throw IllegalStateException("No more watch outcomes configured")
        return when (outcome) {
            is SecretWatchSession -> outcome
            is Throwable -> throw outcome
            else -> throw IllegalStateException("Unsupported watch outcome: ${outcome::class.simpleName}")
        }
    }

    override fun readSecret(namespace: String, name: String): Secret {
        readSecretCalls += 1
        return getResult
    }

    override fun readEncodedValue(namespace: String, name: String, key: String): ByteArray? {
        readEncodedValueCalls += 1
        lastEncodedValueReturned = encodedValue?.copyOf()
        return encodedValue
    }
}

private class FakeSecretWatchSession(
    private val events: List<SecretWatchEvent>,
) : SecretWatchSession {
    override fun iterator(): Iterator<SecretWatchEvent> = events.iterator()
    override fun close() = Unit
}
