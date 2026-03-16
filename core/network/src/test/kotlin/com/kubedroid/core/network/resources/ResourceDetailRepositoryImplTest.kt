package com.kubedroid.core.network.resources

import io.kubernetes.client.openapi.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ResourceDetailRepositoryImplTest {

    @Test
    fun test_getResourceDetail_403_mapsToForbiddenException() = runTest {
        val repository = ResourceDetailRepositoryImpl(
            resourceDetailClient = object : ResourceDetailClient {
                override fun fetch(kind: String, name: String, namespace: String?): ResourceDetail {
                    throw ApiException(403, "forbidden")
                }
            },
            yamlApplyService = FakeYamlApplyService(Result.success(YamlEditResult.NoChanges)),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.getResourceDetail(
            kind = "Pod",
            name = "demo",
            namespace = "default",
        )

        assertTrue(result.isFailure)
        assertIs<ResourceForbiddenException>(result.exceptionOrNull())
    }

    @Test
    fun test_getResourceDetail_404_mapsToResourceDetailApiException() = runTest {
        val repository = ResourceDetailRepositoryImpl(
            resourceDetailClient = object : ResourceDetailClient {
                override fun fetch(kind: String, name: String, namespace: String?): ResourceDetail {
                    throw ApiException(404, "missing")
                }
            },
            yamlApplyService = FakeYamlApplyService(Result.success(YamlEditResult.NoChanges)),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.getResourceDetail(
            kind = "Pod",
            name = "demo",
            namespace = "default",
        )

        assertTrue(result.isFailure)
        val exception = assertIs<ResourceDetailApiException>(result.exceptionOrNull())
        assertEquals("Resource was not found", exception.message)
    }

    @Test
    fun test_getResourceDetail_success_returnsResource() = runTest {
        val detail = ResourceDetail(
            name = "demo",
            namespace = "default",
            kind = "Pod",
            apiVersion = "v1",
            resourceVersion = "7",
            yaml = "apiVersion: v1",
        )

        val repository = ResourceDetailRepositoryImpl(
            resourceDetailClient = object : ResourceDetailClient {
                override fun fetch(kind: String, name: String, namespace: String?): ResourceDetail = detail
            },
            yamlApplyService = FakeYamlApplyService(Result.success(YamlEditResult.NoChanges)),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.getResourceDetail(
            kind = "Pod",
            name = "demo",
            namespace = "default",
        )

        assertTrue(result.isSuccess)
        assertEquals(detail, result.getOrThrow())
    }

    @Test
    fun test_applyYaml_propagatesConflictResult() = runTest {
        val repository = ResourceDetailRepositoryImpl(
            resourceDetailClient = object : ResourceDetailClient {
                override fun fetch(kind: String, name: String, namespace: String?): ResourceDetail {
                    throw AssertionError("not used")
                }
            },
            yamlApplyService = FakeYamlApplyService(
                Result.success(YamlEditResult.Conflict(currentResourceVersion = "12")),
            ),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.applyYaml(
            kind = "Pod",
            name = "demo",
            yaml = "apiVersion: v1",
            namespace = "default",
        )

        assertTrue(result.isSuccess)
        val conflict = assertIs<YamlEditResult.Conflict>(result.getOrThrow())
        assertEquals("12", conflict.currentResourceVersion)
    }

    @Test
    fun test_applyYaml_mapsUnexpectedFailureToResourceDetailApiException() = runTest {
        val repository = ResourceDetailRepositoryImpl(
            resourceDetailClient = object : ResourceDetailClient {
                override fun fetch(kind: String, name: String, namespace: String?): ResourceDetail {
                    throw AssertionError("not used")
                }
            },
            yamlApplyService = FakeYamlApplyService(Result.failure(IllegalStateException("boom"))),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.applyYaml(
            kind = "Pod",
            name = "demo",
            yaml = "apiVersion: v1",
            namespace = "default",
        )

        assertTrue(result.isFailure)
        val exception = assertIs<ResourceDetailApiException>(result.exceptionOrNull())
        assertEquals("boom", exception.message)
    }

    private class FakeYamlApplyService(
        private val result: Result<YamlEditResult>,
    ) : YamlApplyService {
        override suspend fun applyYaml(
            kind: String,
            name: String,
            yaml: String,
            namespace: String?,
        ): Result<YamlEditResult> = result
    }
}
