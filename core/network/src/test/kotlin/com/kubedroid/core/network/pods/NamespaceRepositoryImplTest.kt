package com.kubedroid.core.network.pods

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class NamespaceRepositoryImplTest {

    @Test
    fun test_listNamespaces_403_mapsToNamespaceForbidden() = runTest {
        val repository = NamespaceRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            namespaceApiFactory = NamespaceApiFactory {
                object : NamespaceApi {
                    override fun listNamespaces(): List<String> {
                        throw ApiException(403, "forbidden")
                    }
                }
            },
        )

        val result = repository.listNamespaces()

        assertTrue(result.isFailure)
        assertIs<NamespaceForbiddenException>(result.exceptionOrNull())
    }

    @Test
    fun test_listNamespaces_mapsAndSortsNames() = runTest {
        val repository = NamespaceRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            namespaceApiFactory = NamespaceApiFactory {
                object : NamespaceApi {
                    override fun listNamespaces(): List<String> = listOf("kube-system", "", "default")
                }
            },
        )

        val result = repository.listNamespaces()

        assertTrue(result.isSuccess)
        assertEquals(listOf("default", "kube-system"), result.getOrThrow())
    }

    @Test
    fun test_listNamespaces_apiException_mapsToNamespaceApiException() = runTest {
        val repository = NamespaceRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            namespaceApiFactory = NamespaceApiFactory {
                object : NamespaceApi {
                    override fun listNamespaces(): List<String> {
                        throw ApiException(500, "internal error")
                    }
                }
            },
        )

        val result = repository.listNamespaces()

        assertTrue(result.isFailure)
        assertIs<NamespaceApiException>(result.exceptionOrNull())
    }

    @Test
    fun test_listNamespaces_nonApiException_mapsToNamespaceApiException() = runTest {
        val repository = NamespaceRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            namespaceApiFactory = NamespaceApiFactory {
                object : NamespaceApi {
                    override fun listNamespaces(): List<String> {
                        throw IllegalStateException("boom")
                    }
                }
            },
        )

        val result = repository.listNamespaces()

        assertTrue(result.isFailure)
        assertIs<NamespaceApiException>(result.exceptionOrNull())
    }
}
