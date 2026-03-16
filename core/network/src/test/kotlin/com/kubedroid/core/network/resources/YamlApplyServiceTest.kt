package com.kubedroid.core.network.resources

import com.google.gson.JsonObject
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class YamlApplyServiceTest {

    @Test
    fun test_applyYaml_blankKind_returnsFailure() = runTest {
        val service = DefaultYamlApplyService(
            yamlParser = ResourceYamlParser { parsedPodResource(resourceVersion = "9") },
            yamlApplyClient = FakeYamlApplyClient(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = service.applyYaml(
            kind = "  ",
            name = "demo",
            yaml = "ignored",
            namespace = "default",
        )

        assertTrue(result.isFailure)
        val exception = assertIs<InvalidYamlException>(result.exceptionOrNull())
        assertEquals("YAML kind cannot be blank", exception.message)
    }

    @Test
    fun test_applyYaml_invalidYaml_returnsFailure() = runTest {
        val service = DefaultYamlApplyService(
            yamlParser = ResourceYamlParser { throw InvalidYamlException("bad yaml") },
            yamlApplyClient = FakeYamlApplyClient(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = service.applyYaml(
            kind = "Pod",
            name = "demo",
            yaml = "invalid",
            namespace = "default",
        )

        assertTrue(result.isFailure)
        assertIs<InvalidYamlException>(result.exceptionOrNull())
    }

    @Test
    fun test_applyYaml_http409_returnsConflictWithCurrentResourceVersion() = runTest {
        val parsed = parsedPodResource(resourceVersion = "9")
        val service = DefaultYamlApplyService(
            yamlParser = ResourceYamlParser { parsed },
            yamlApplyClient = FakeYamlApplyClient(
                applyError = ApiException(409, "conflict"),
                currentResourceVersion = "10",
            ),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = service.applyYaml(
            kind = "Pod",
            name = "demo",
            yaml = "ignored",
            namespace = "default",
        )

        assertTrue(result.isSuccess)
        val conflict = assertIs<YamlEditResult.Conflict>(result.getOrThrow())
        assertEquals("10", conflict.currentResourceVersion)
    }

    @Test
    fun test_applyYaml_http409_whenCurrentVersionLookupFails_returnsConflictWithUnknownVersion() = runTest {
        val parsed = parsedPodResource(resourceVersion = "9")
        val service = DefaultYamlApplyService(
            yamlParser = ResourceYamlParser { parsed },
            yamlApplyClient = FakeYamlApplyClient(
                applyError = ApiException(409, "conflict"),
                currentVersionError = ApiException(500, "lookup failed"),
            ),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = service.applyYaml(
            kind = "Pod",
            name = "demo",
            yaml = "ignored",
            namespace = "default",
        )

        assertTrue(result.isSuccess)
        val conflict = assertIs<YamlEditResult.Conflict>(result.getOrThrow())
        assertNull(conflict.currentResourceVersion)
    }

    @Test
    fun test_applyYaml_http403_returnsForbiddenFailure() = runTest {
        val service = DefaultYamlApplyService(
            yamlParser = ResourceYamlParser { parsedPodResource(resourceVersion = "9") },
            yamlApplyClient = FakeYamlApplyClient(applyError = ApiException(403, "forbidden")),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = service.applyYaml(
            kind = "Pod",
            name = "demo",
            yaml = "ignored",
            namespace = "default",
        )

        assertTrue(result.isFailure)
        assertIs<YamlApplyForbiddenException>(result.exceptionOrNull())
    }

    @Test
    fun test_applyYaml_success_returnsApplied() = runTest {
        val service = DefaultYamlApplyService(
            yamlParser = ResourceYamlParser { parsedPodResource(resourceVersion = "9") },
            yamlApplyClient = FakeYamlApplyClient(
                applyResponse = YamlApplyResponse(resourceVersion = "10", changed = true),
            ),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = service.applyYaml(
            kind = "Pod",
            name = "demo",
            yaml = "ignored",
            namespace = "default",
        )

        assertTrue(result.isSuccess)
        val applied = assertIs<YamlEditResult.Applied>(result.getOrThrow())
        assertEquals("10", applied.resourceVersion)
    }

    @Test
    fun test_applyYaml_success_noChanges_returnsNoChanges() = runTest {
        val service = DefaultYamlApplyService(
            yamlParser = ResourceYamlParser { parsedPodResource(resourceVersion = "9") },
            yamlApplyClient = FakeYamlApplyClient(
                applyResponse = YamlApplyResponse(resourceVersion = "9", changed = false),
            ),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = service.applyYaml(
            kind = "Pod",
            name = "demo",
            yaml = "ignored",
            namespace = "default",
        )

        assertTrue(result.isSuccess)
        assertIs<YamlEditResult.NoChanges>(result.getOrThrow())
    }

    @Test
    fun test_applyYaml_addsRequestedNamespace_whenYamlNamespaceMissing() = runTest {
        val parsed = parsedPodResource(resourceVersion = "9", namespace = null)
        val applyClient = FakeYamlApplyClient(
            applyResponse = YamlApplyResponse(resourceVersion = "10", changed = true),
        )
        val service = DefaultYamlApplyService(
            yamlParser = ResourceYamlParser { parsed },
            yamlApplyClient = applyClient,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = service.applyYaml(
            kind = "Pod",
            name = "demo",
            yaml = "ignored",
            namespace = "prod",
        )

        assertTrue(result.isSuccess)
        assertEquals("prod", applyClient.lastAppliedNamespace)
    }

    private fun parsedPodResource(
        resourceVersion: String?,
        namespace: String? = "default",
    ): ParsedYamlResource {
        val json = JsonObject()
        val dynamicObject = DynamicKubernetesObject(json).apply {
            apiVersion = "v1"
            kind = "Pod"
            metadata = io.kubernetes.client.openapi.models.V1ObjectMeta().apply {
                name = "demo"
                this.namespace = namespace
                this.resourceVersion = resourceVersion
            }
        }

        return ParsedYamlResource(
            raw = json,
            dynamicObject = dynamicObject,
            apiVersion = "v1",
            kind = "Pod",
            name = "demo",
            namespace = namespace,
            resourceVersion = resourceVersion,
        )
    }

    private class FakeYamlApplyClient(
        private val applyResponse: YamlApplyResponse = YamlApplyResponse(resourceVersion = "10", changed = true),
        private val applyError: Throwable? = null,
        private val currentResourceVersion: String? = null,
        private val currentVersionError: Throwable? = null,
    ) : YamlApplyClient {
        var lastAppliedNamespace: String? = null

        override fun apply(resource: ParsedYamlResource): YamlApplyResponse {
            applyError?.let { throw it }
            lastAppliedNamespace = resource.dynamicObject.metadata?.namespace
            return applyResponse
        }

        override fun fetchCurrentResourceVersion(resource: ParsedYamlResource): String? {
            currentVersionError?.let { throw it }
            return currentResourceVersion
        }
    }
}
