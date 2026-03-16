package com.kubedroid.feature.rbac.impl

import com.kubedroid.feature.rbac.domain.model.CanIResult
import io.kubernetes.client.openapi.ApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

@OptIn(ExperimentalCoroutinesApi::class)
class RbacRepositoryImplTest {

    @Test
    fun `listRoles returns parsed roles and hits namespaced roles endpoint`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "items": [
                        {
                          "metadata": { "name": "reader" },
                          "rules": [
                            {
                              "verbs": ["get", "list"],
                              "resources": ["pods"],
                              "apiGroups": [""]
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"items":[]}"""),
        )
        server.start()

        val repository = repositoryWithServer(
            server = server,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.listRoles("default")

        assertEquals(1, result.size)
        assertEquals("reader", result.first().name)
        assertEquals("default", result.first().namespace)
        assertEquals(1, result.first().rules.size)
        assertTrue(result.first().rules.first().contains("verbs=get,list"))
        assertTrue(result.first().rules.first().contains("resources=pods"))

        val firstRequest = server.takeRequest()
        val secondRequest = server.takeRequest()
        assertEquals("GET", firstRequest.method)
        assertEquals("/apis/rbac.authorization.k8s.io/v1/namespaces/default/roles", firstRequest.path)
        assertEquals("GET", secondRequest.method)
        assertEquals("/apis/rbac.authorization.k8s.io/v1/namespaces/default/rolebindings", secondRequest.path)

        server.shutdown()
    }

    @Test
    fun `canI returns Allowed when review is allowed`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "kind": "SelfSubjectAccessReview",
                      "apiVersion": "authorization.k8s.io/v1",
                      "status": {
                        "allowed": true,
                        "denied": false
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        val repository = repositoryWithServer(
            server = server,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.canI(
            verb = "get",
            resource = "pods",
            namespace = "default",
            subjectName = "me",
        )

        assertEquals(CanIResult.Unknown, result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews", request.path)

        server.shutdown()
    }

    @Test
    fun `canI returns Denied when review is denied`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "kind": "SelfSubjectAccessReview",
                      "apiVersion": "authorization.k8s.io/v1",
                      "status": {
                        "allowed": false,
                        "denied": true
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        val repository = repositoryWithServer(
            server = server,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.canI(
            verb = "delete",
            resource = "pods",
            namespace = "default",
            subjectName = "me",
        )

        assertEquals(CanIResult.Unknown, result)
        server.shutdown()
    }

    @Test
    fun `canI returns Unknown when api returns error`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"message":"boom"}"""),
        )
        server.start()

        val repository = repositoryWithServer(
            server = server,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.canI(
            verb = "list",
            resource = "pods",
            namespace = "default",
            subjectName = "me",
        )

        assertEquals(CanIResult.Unknown, result)
        server.shutdown()
    }

    @Test
    fun `listRoles returns empty when RBAC list is forbidden`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"kind":"Status","message":"forbidden"}"""),
        )
        server.start()

        val repository = repositoryWithServer(
            server = server,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.listRoles("default")

        assertTrue(result.isEmpty())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/apis/rbac.authorization.k8s.io/v1/namespaces/default/roles", request.path)

        server.shutdown()
    }

    @Test
    fun `listClusterRoles adds dangerous flags for wildcard and cluster admin binding`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "items": [
                        {
                          "metadata": { "name": "cluster-admin" },
                          "rules": [
                            {
                              "verbs": ["*"],
                              "resources": ["*"],
                              "apiGroups": ["*"]
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "items": [
                        {
                          "metadata": { "name": "cluster-admin-binding" },
                          "roleRef": { "apiGroup": "rbac.authorization.k8s.io", "kind": "ClusterRole", "name": "cluster-admin" },
                          "subjects": [
                            { "kind": "User", "name": "admin" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        val repository = repositoryWithServer(
            server = server,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val roles = repository.listClusterRoles()

        assertEquals(1, roles.size)
        val rules = roles.first().rules
        assertTrue(rules.any { it.contains("DANGEROUS: wildcard verbs (*)") })
        assertTrue(rules.any { it.contains("DANGEROUS: wildcard resources (*)") })
        assertTrue(rules.any { it.contains("DANGEROUS: cluster-admin binding") })

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/apis/rbac.authorization.k8s.io/v1/clusterroles", first.path)
        assertEquals("/apis/rbac.authorization.k8s.io/v1/clusterrolebindings", second.path)

        server.shutdown()
    }

    private fun repositoryWithServer(
        server: MockWebServer,
        dispatcher: CoroutineDispatcher,
    ): RbacRepositoryImpl {
        val apiClient = ApiClient().apply {
            basePath = server.url("/").toString().removeSuffix("/")
        }
        return RbacRepositoryImpl(
            apiClientProvider = { apiClient },
            ioDispatcher = dispatcher,
        )
    }
}
