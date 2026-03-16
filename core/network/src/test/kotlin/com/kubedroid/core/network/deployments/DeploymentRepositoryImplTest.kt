package com.kubedroid.core.network.deployments

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1DeploymentSpec
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1OwnerReference
import io.kubernetes.client.openapi.models.V1PodTemplateSpec
import io.kubernetes.client.openapi.models.V1ReplicaSet
import io.kubernetes.client.openapi.models.V1ReplicaSetSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DeploymentRepositoryImplTest {

    @Test
    fun test_list_emptyNamespace_returnsFailure() = runTest {
        val repository = DeploymentRepositoryImpl(
            apiClientProvider = { ApiClient() },
            deploymentApiFactory = DeploymentApiFactory { FakeDeploymentApi() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.list("  ")

        assertTrue(result.isFailure)
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun test_scale_invalidReplicaCount_returnsInvalidReplicaCount() = runTest {
        val fakeApi = FakeDeploymentApi()
        val repository = DeploymentRepositoryImpl(
            apiClientProvider = { ApiClient() },
            deploymentApiFactory = DeploymentApiFactory { fakeApi },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.scale(
            namespace = "default",
            deploymentName = "api",
            replicas = -1,
        )

        assertIs<ScaleResult.InvalidReplicaCount>(result)
        assertEquals(0, fakeApi.readCalls)
        assertEquals(0, fakeApi.patchCalls)
    }

    @Test
    fun test_scale_success_trimsInputsAndReturnsReplicaCounts() = runTest {
        val fakeApi = FakeDeploymentApi(
            readDeploymentResult = deploymentSpecReplicas(2),
            patchDeploymentResult = deploymentSpecReplicas(5),
        )
        val repository = DeploymentRepositoryImpl(
            apiClientProvider = { ApiClient() },
            deploymentApiFactory = DeploymentApiFactory { fakeApi },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.scale(
            namespace = "  default  ",
            deploymentName = "  api  ",
            replicas = 5,
        )

        val success = assertIs<ScaleResult.Success>(result)
        assertEquals("default", success.namespace)
        assertEquals("api", success.deploymentName)
        assertEquals(2, success.previousReplicas)
        assertEquals(5, success.requestedReplicas)
        assertEquals(5, success.currentReplicas)
        assertEquals("default", fakeApi.lastReadNamespace)
        assertEquals("api", fakeApi.lastReadDeploymentName)
        assertEquals("default", fakeApi.lastPatchNamespace)
        assertEquals("api", fakeApi.lastPatchDeploymentName)
        assertEquals("{\"spec\":{\"replicas\":5}}", fakeApi.lastPatchBody)
    }

    @Test
    fun test_scale_404_mapsToNotFound() = runTest {
        val repository = DeploymentRepositoryImpl(
            apiClientProvider = { ApiClient() },
            deploymentApiFactory = DeploymentApiFactory {
                FakeDeploymentApi(
                    readDeploymentResult = ApiException(404, "missing"),
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.scale(
            namespace = "default",
            deploymentName = "api",
            replicas = 3,
        )

        assertIs<ScaleResult.NotFound>(result)
    }

    @Test
    fun test_history_filtersByOwnerAndSortsByRevisionDesc() = runTest {
        val repository = DeploymentRepositoryImpl(
            apiClientProvider = { ApiClient() },
            deploymentApiFactory = DeploymentApiFactory {
                FakeDeploymentApi(
                    replicaSets = listOf(
                        replicaSet(
                            deploymentName = "api",
                            revision = "2",
                            changeCause = "scale to 2",
                        ),
                        replicaSet(
                            deploymentName = "api",
                            revision = "5",
                            changeCause = "release v5",
                        ),
                        replicaSet(
                            deploymentName = "other",
                            revision = "7",
                            changeCause = "ignored owner",
                        ),
                        replicaSet(
                            deploymentName = "api",
                            revision = "not-a-number",
                            changeCause = "ignored invalid revision",
                        ),
                    ),
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.history(
            namespace = "default",
            deploymentName = "api",
        )

        assertTrue(result.isSuccess)
        val revisions = result.getOrThrow()
        assertEquals(listOf(5L, 2L), revisions.map { it.revision })
        assertEquals(listOf("release v5", "scale to 2"), revisions.map { it.changeCause })
    }

    @Test
    fun test_rollback_withoutRevision_picksPreviousRevisionAndPatchesTemplate() = runTest {
        val fakeApi = FakeDeploymentApi(
            replicaSets = listOf(
                replicaSet(deploymentName = "api", revision = "7", templateName = "current"),
                replicaSet(deploymentName = "api", revision = "6", templateName = "previous"),
            ),
        )
        val repository = DeploymentRepositoryImpl(
            apiClientProvider = { ApiClient() },
            deploymentApiFactory = DeploymentApiFactory { fakeApi },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.rollback(
            namespace = "default",
            deploymentName = "api",
            toRevision = null,
        )

        val success = assertIs<RolloutResult.Success>(result)
        assertEquals(RolloutResult.Action.ROLLBACK, success.action)
        assertEquals(6L, success.targetRevision)
        assertEquals("default", fakeApi.lastPatchNamespace)
        assertEquals("api", fakeApi.lastPatchDeploymentName)
        assertTrue(fakeApi.lastPatchBody?.contains("\"template\"") == true)
        assertTrue(fakeApi.lastPatchBody?.contains("previous") == true)
    }

    @Test
    fun test_rollback_targetRevisionMissing_returnsFailure() = runTest {
        val repository = DeploymentRepositoryImpl(
            apiClientProvider = { ApiClient() },
            deploymentApiFactory = DeploymentApiFactory {
                FakeDeploymentApi(
                    replicaSets = listOf(
                        replicaSet(deploymentName = "api", revision = "3", templateName = "only"),
                    ),
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.rollback(
            namespace = "default",
            deploymentName = "api",
            toRevision = 99L,
        )

        val failure = assertIs<RolloutResult.Failure>(result)
        assertTrue(failure.cause.message?.contains("99") == true)
    }

    private class FakeDeploymentApi(
        private val readDeploymentResult: Any = deploymentSpecReplicas(0),
        private val patchDeploymentResult: Any = deploymentSpecReplicas(0),
        private val replicaSets: List<V1ReplicaSet> = emptyList(),
    ) : DeploymentApi {

        var readCalls: Int = 0
        var patchCalls: Int = 0
        var lastReadNamespace: String? = null
        var lastReadDeploymentName: String? = null
        var lastPatchNamespace: String? = null
        var lastPatchDeploymentName: String? = null
        var lastPatchBody: String? = null

        override fun readDeployment(namespace: String, deploymentName: String): V1Deployment {
            readCalls += 1
            lastReadNamespace = namespace
            lastReadDeploymentName = deploymentName
            return when (readDeploymentResult) {
                is V1Deployment -> readDeploymentResult
                is Throwable -> throw readDeploymentResult
                else -> throw IllegalStateException("Unsupported read result: ${readDeploymentResult::class.simpleName}")
            }
        }

        override fun listDeployments(namespace: String): List<Deployment> = emptyList()

        override fun listDeploymentsSnapshot(namespace: String): DeploymentListSnapshot {
            return DeploymentListSnapshot(deployments = emptyList(), resourceVersion = "1")
        }

        override fun listReplicaSets(namespace: String): List<V1ReplicaSet> = replicaSets

        override fun openDeploymentWatch(namespace: String, resourceVersion: String?): DeploymentWatchSession {
            throw AssertionError("watch not used in this test")
        }

        override fun patchDeployment(namespace: String, deploymentName: String, patchBody: String): V1Deployment {
            patchCalls += 1
            lastPatchNamespace = namespace
            lastPatchDeploymentName = deploymentName
            lastPatchBody = patchBody
            return when (patchDeploymentResult) {
                is V1Deployment -> patchDeploymentResult
                is Throwable -> throw patchDeploymentResult
                else -> throw IllegalStateException("Unsupported patch result: ${patchDeploymentResult::class.simpleName}")
            }
        }
    }

    companion object {
        private fun deploymentSpecReplicas(replicas: Int): V1Deployment {
            return V1Deployment().apply {
                spec = V1DeploymentSpec().apply {
                    this.replicas = replicas
                }
            }
        }

        private fun replicaSet(
            deploymentName: String,
            revision: String,
            changeCause: String? = null,
            templateName: String = "template-$revision",
        ): V1ReplicaSet {
            val annotations = mutableMapOf(
                "deployment.kubernetes.io/revision" to revision,
            )
            if (changeCause != null) {
                annotations["kubernetes.io/change-cause"] = changeCause
            }

            return V1ReplicaSet().apply {
                metadata = V1ObjectMeta().apply {
                    ownerReferences = listOf(
                        V1OwnerReference().apply {
                            kind = "Deployment"
                            name = deploymentName
                        },
                    )
                    this.annotations = annotations
                }
                spec = V1ReplicaSetSpec().apply {
                    template = V1PodTemplateSpec().apply {
                        metadata = V1ObjectMeta().apply {
                            name = templateName
                        }
                    }
                }
            }
        }
    }
}
