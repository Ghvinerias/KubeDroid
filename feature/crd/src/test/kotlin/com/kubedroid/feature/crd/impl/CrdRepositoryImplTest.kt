package com.kubedroid.feature.crd.impl

import com.kubedroid.feature.crd.local.FavouriteCrd
import com.kubedroid.feature.crd.local.FavouriteCrdDao
import com.kubedroid.feature.crd.model.CustomResourceDefinition
import io.kubernetes.client.openapi.ApiClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrdRepositoryImplTest {

    @Test
    fun `listCrds success maps and sorts definitions and removes stale favourites`() = runTest {
        val favouriteDao = FakeFavouriteCrdDao(
            initial = listOf(
                FavouriteCrd(
                    name = "ghosts.example.com",
                    group = "example.com",
                    version = "v1",
                    scope = "Namespaced",
                    kind = "Ghost",
                ),
                FavouriteCrd(
                    name = "alphas.example.com",
                    group = "example.com",
                    version = "v1",
                    scope = "Namespaced",
                    kind = "Alpha",
                ),
            ),
        )
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(crdListJson()),
        )
        server.start()

        val repository = repositoryWithServer(
            server = server,
            favouriteDao = favouriteDao,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.listCrds()

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString() ?: "expected success")
        val items = result.getOrThrow()
        assertEquals(listOf("alphas.example.com", "zeds.example.com"), items.map { it.name })
        assertEquals(listOf("example.com/v1/Ghost"), favouriteDao.deletedKeys)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/apis/apiextensions.k8s.io/v1/customresourcedefinitions", request.path)

        server.shutdown()
    }

    @Test
    fun `listCustomResources namespaced CRD with blank namespace returns scope mismatch`() = runTest {
        val repository = CrdRepositoryImpl(
            apiClientProvider = { ApiClient() },
            favouriteDao = FakeFavouriteCrdDao(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.listCustomResources(
            crd = namespacedCrd(),
            namespace = "   ",
        )

        assertTrue(result.isFailure)
        assertIs<CrdScopeMismatchException>(result.exceptionOrNull())
    }

    @Test
    fun `listCustomResources 403 maps to permission denied`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("{\"kind\":\"Status\",\"message\":\"forbidden\"}"),
        )
        server.start()

        val repository = repositoryWithServer(
            server = server,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.listCustomResources(
            crd = namespacedCrd(),
            namespace = "default",
        )

        assertTrue(result.isFailure)
        val error = assertIs<CrdPermissionDeniedException>(result.exceptionOrNull())
        assertEquals("default", error.namespace)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path.orEmpty().contains("/apis/example.com/v1/namespaces/default/widgets"))

        server.shutdown()
    }

    @Test
    fun `favourite behaviors via repository set and query and observe`() = runTest {
        val favouriteDao = FakeFavouriteCrdDao()
        val repository = CrdRepositoryImpl(
            apiClientProvider = { ApiClient() },
            favouriteDao = favouriteDao,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val crd = namespacedCrd()

        assertTrue(repository.observeFavouriteCrds().first().isEmpty())
        assertFalse(repository.isFavourite(crd))

        repository.setFavourite(crd, favourite = true)

        assertTrue(repository.isFavourite(crd))
        assertEquals(listOf(crd), repository.observeFavouriteCrds().first())

        repository.setFavourite(crd, favourite = false)

        assertFalse(repository.isFavourite(crd))
        assertTrue(repository.observeFavouriteCrds().first().isEmpty())
    }

    private fun repositoryWithServer(
        server: MockWebServer,
        favouriteDao: FavouriteCrdDao = FakeFavouriteCrdDao(),
        dispatcher: TestDispatcher,
    ): CrdRepositoryImpl {
        val apiClient = ApiClient().apply {
            basePath = server.url("/").toString().removeSuffix("/")
        }
        return CrdRepositoryImpl(
            apiClientProvider = { apiClient },
            favouriteDao = favouriteDao,
            ioDispatcher = dispatcher,
        )
    }

    private fun namespacedCrd(): CustomResourceDefinition {
        return CustomResourceDefinition(
            name = "widgets.example.com",
            group = "example.com",
            version = "v1",
            scope = "Namespaced",
            kind = "Widget",
        )
    }

    private fun crdListJson(): String {
        return """
            {
              "apiVersion": "apiextensions.k8s.io/v1",
              "kind": "CustomResourceDefinitionList",
              "items": [
                {
                  "apiVersion": "apiextensions.k8s.io/v1",
                  "kind": "CustomResourceDefinition",
                  "metadata": { "name": "zeds.example.com" },
                  "spec": {
                    "group": "example.com",
                    "scope": "Namespaced",
                    "names": {
                      "kind": "Zed",
                      "plural": "zeds",
                      "singular": "zed"
                    },
                    "versions": [
                      { "name": "v1", "served": true, "storage": true }
                    ]
                  }
                },
                {
                  "apiVersion": "apiextensions.k8s.io/v1",
                  "kind": "CustomResourceDefinition",
                  "metadata": { "name": "alphas.example.com" },
                  "spec": {
                    "group": "example.com",
                    "scope": "Namespaced",
                    "names": {
                      "kind": "Alpha",
                      "plural": "alphas",
                      "singular": "alpha"
                    },
                    "versions": [
                      { "name": "v1", "served": true, "storage": true }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }
}

private class FakeFavouriteCrdDao(
    initial: List<FavouriteCrd> = emptyList(),
) : FavouriteCrdDao {

    private val state = MutableStateFlow(initial.sortedBy { it.name })
    val deletedKeys = mutableListOf<String>()

    override fun observeAll(): Flow<List<FavouriteCrd>> = state

    override suspend fun listAll(): List<FavouriteCrd> = state.value

    override suspend fun upsert(favouriteCrd: FavouriteCrd) {
        val filtered = state.value.filterNot {
            it.group == favouriteCrd.group &&
                it.version == favouriteCrd.version &&
                it.kind == favouriteCrd.kind
        }
        state.value = (filtered + favouriteCrd).sortedBy { it.name }
    }

    override suspend fun delete(group: String, version: String, kind: String) {
        deletedKeys += "$group/$version/$kind"
        state.value = state.value.filterNot {
            it.group == group &&
                it.version == version &&
                it.kind == kind
        }
    }

    override suspend fun exists(group: String, version: String, kind: String): Boolean {
        return state.value.any {
            it.group == group &&
                it.version == version &&
                it.kind == kind
        }
    }
}
