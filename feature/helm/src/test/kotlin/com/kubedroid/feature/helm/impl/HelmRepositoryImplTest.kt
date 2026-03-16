package com.kubedroid.feature.helm.impl

import com.google.gson.JsonParser
import io.kubernetes.client.openapi.ApiClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class HelmRepositoryImplTest {

    @Test
    fun `listReleases decodes deployed release secret`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    secretListJson(
                        secrets = listOf(
                            secretJson(
                                secretName = "sh.helm.release.v1.demo.v7",
                                releaseName = "demo",
                                namespace = "prod",
                                revision = 7,
                                statusLabel = "deployed",
                                releaseData = releaseDataField(
                                    name = "demo",
                                    namespace = "prod",
                                    revision = 7,
                                    status = 1,
                                    chartName = "nginx",
                                    chartVersion = "1.2.3",
                                    appVersion = "9.8.7",
                                    description = "rolled out",
                                    manifest = "kind: ConfigMap",
                                    deployedSeconds = 1_700_000_000L,
                                ),
                            ),
                        ),
                    ),
                ),
        )
        server.start()

        val repository = testRepository(server = server, testSchedulerDispatcher = StandardTestDispatcher(testScheduler))
        val result = repository.listReleases("prod")
        val request = server.takeRequest()

        assertTrue(result.isSuccess)
        val releases = result.getOrThrow()
        assertEquals(1, releases.size)
        assertEquals("demo", releases.first().name)
        assertEquals("prod", releases.first().namespace)
        assertEquals("nginx", releases.first().chart)
        assertEquals("1.2.3", releases.first().chartVersion)
        assertEquals("9.8.7", releases.first().appVersion)
        assertEquals("deployed", releases.first().status)
        assertNotNull(releases.first().lastDeployed)
        assertEquals("GET", request.method)
        assertEquals(
            "/api/v1/namespaces/prod/secrets?labelSelector=owner%3Dhelm",
            request.path,
        )

        server.shutdown()
    }

    @Test
    fun `codec fails on invalid base64 data`() {
        val failure = runCatching {
            HelmReleaseSecretCodec.decode(
                secretName = "broken",
                rawReleaseField = "not-base64".toByteArray(),
            )
        }.exceptionOrNull()

        val error = assertIs<ReleaseDecodeException>(failure)
        assertTrue(error.message.orEmpty().contains("base64"))
    }

    @Test
    fun `rollbackRelease creates next deployed secret revision`() = runTest {
        val revisionOnePayload = releaseDataField(
            name = "demo",
            namespace = "prod",
            revision = 1,
            status = 3,
            chartName = "demo-chart",
            chartVersion = "0.1.1",
            appVersion = "1.1.0",
            description = "revision 1",
            manifest = "kind: Deployment",
            deployedSeconds = 1_700_000_001L,
        )
        serverWithRollbackResponses(
            revisionOnePayload = revisionOnePayload,
            revisionTwoPayload = releaseDataField(
                name = "demo",
                namespace = "prod",
                revision = 2,
                status = 1,
                chartName = "demo-chart",
                chartVersion = "0.1.2",
                appVersion = "1.2.0",
                description = "revision 2",
                manifest = "kind: Deployment",
                deployedSeconds = 1_700_000_002L,
            ),
        ).use { server ->
            val repository = testRepository(
                server = server,
                testSchedulerDispatcher = StandardTestDispatcher(testScheduler),
            )

            val result = repository.rollbackRelease(
                name = "demo",
                namespace = "prod",
                revision = 1,
            )

            assertTrue(result.isSuccess, "rollbackRelease failed: ${result.exceptionOrNull()}")
            val initialListRequest = server.takeRequest()
            val latestListRequest = server.takeRequest()
            val createRequest = server.takeRequest()
            assertEquals("GET", initialListRequest.method)
            assertEquals("GET", latestListRequest.method)
            assertEquals(
                "/api/v1/namespaces/prod/secrets?labelSelector=owner%3Dhelm%2Cname%3Ddemo",
                initialListRequest.path,
            )
            assertEquals(initialListRequest.path, latestListRequest.path)
            assertEquals("POST", createRequest.method)
            assertEquals("/api/v1/namespaces/prod/secrets", createRequest.path)

            val body = createRequest.body.readUtf8()
            assertTrue(body.contains("\"name\":\"sh.helm.release.v1.demo.v3\""))
            assertTrue(body.contains("\"status\":\"deployed\""))
            assertTrue(body.contains("\"version\":\"3\""))

            val releaseField = extractReleaseField(body)
            val decoded = HelmReleaseSecretCodec.decode(
                secretName = "sh.helm.release.v1.demo.v3",
                rawReleaseField = Base64.getDecoder().decode(releaseField),
            )
            assertEquals(3, decoded.revision)
            assertEquals("deployed", decoded.status)
            assertEquals("demo-chart", decoded.chartName)
            assertEquals("0.1.1", decoded.chartVersion)
        }
    }

    @Test
    fun `listReleases returns NoHelmReleases when empty list returned`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(secretListJson(secrets = emptyList())),
        )
        server.start()

        val repository = testRepository(
            server = server,
            testSchedulerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val result = repository.listReleases("prod")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
        server.shutdown()
    }

    @Test
    fun `listReleases returns decode failure when release payload cannot be decoded`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    secretListJson(
                        secrets = listOf(
                            secretJson(
                                secretName = "sh.helm.release.v1.demo.v1",
                                releaseName = "demo",
                                namespace = "prod",
                                revision = 1,
                                statusLabel = "deployed",
                                releaseData = "not-valid-base64".toByteArray(Charsets.UTF_8),
                            ),
                        ),
                    ),
                ),
        )
        server.start()

        val repository = testRepository(
            server = server,
            testSchedulerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val result = repository.listReleases("prod")

        assertTrue(result.isFailure)
        assertIs<ReleaseDecodeException>(result.exceptionOrNull())
        server.shutdown()
    }

    @Test
    fun `rollbackRelease fails for invalid revision`() = runTest {
        serverWithRollbackResponses(
            revisionOnePayload = releaseDataField(
                name = "demo",
                namespace = "prod",
                revision = 1,
                status = 3,
                chartName = "demo-chart",
                chartVersion = "0.1.1",
                appVersion = "1.1.0",
                description = "revision 1",
                manifest = "kind: Deployment",
                deployedSeconds = 1_700_000_001L,
            ),
            revisionTwoPayload = releaseDataField(
                name = "demo",
                namespace = "prod",
                revision = 2,
                status = 1,
                chartName = "demo-chart",
                chartVersion = "0.1.2",
                appVersion = "1.2.0",
                description = "revision 2",
                manifest = "kind: Deployment",
                deployedSeconds = 1_700_000_002L,
            ),
        ).use { server ->
            val repository = testRepository(
                server = server,
                testSchedulerDispatcher = StandardTestDispatcher(testScheduler),
            )

            val result = repository.rollbackRelease(
                name = "demo",
                namespace = "prod",
                revision = 99,
            )

            assertTrue(result.isFailure)
            assertIs<InvalidRollbackRevisionException>(result.exceptionOrNull())
            val listRequest = server.takeRequest()
            assertEquals("GET", listRequest.method)
            assertFalse(server.requestCount > 1)
        }
    }
}

private fun serverWithRollbackResponses(
    revisionOnePayload: ByteArray,
    revisionTwoPayload: ByteArray,
): MockWebServer {
    val server = MockWebServer()
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                secretListJson(
                    secrets = listOf(
                        secretJson(
                            secretName = "sh.helm.release.v1.demo.v1",
                            releaseName = "demo",
                            namespace = "prod",
                            revision = 1,
                            statusLabel = "superseded",
                            releaseData = revisionOnePayload,
                        ),
                        secretJson(
                            secretName = "sh.helm.release.v1.demo.v2",
                            releaseName = "demo",
                            namespace = "prod",
                            revision = 2,
                            statusLabel = "deployed",
                            releaseData = revisionTwoPayload,
                        ),
                    ),
                ),
            ),
    )
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                secretListJson(
                    secrets = listOf(
                        secretJson(
                            secretName = "sh.helm.release.v1.demo.v1",
                            releaseName = "demo",
                            namespace = "prod",
                            revision = 1,
                            statusLabel = "superseded",
                            releaseData = revisionOnePayload,
                        ),
                        secretJson(
                            secretName = "sh.helm.release.v1.demo.v2",
                            releaseName = "demo",
                            namespace = "prod",
                            revision = 2,
                            statusLabel = "deployed",
                            releaseData = revisionTwoPayload,
                        ),
                    ),
                ),
            ),
    )
    server.enqueue(
        MockResponse()
            .setResponseCode(201)
            .setBody(
                secretJson(
                    secretName = "sh.helm.release.v1.demo.v3",
                    releaseName = "demo",
                    namespace = "prod",
                    revision = 3,
                    statusLabel = "deployed",
                    releaseData = revisionOnePayload,
                ),
            ),
    )
    server.start()
    return server
}

private fun testRepository(
    server: MockWebServer,
    testSchedulerDispatcher: TestDispatcher,
): HelmRepositoryImpl {
    val apiClient = ApiClient().apply {
        basePath = server.url("/").toString().removeSuffix("/")
    }
    return HelmRepositoryImpl(
        apiClientProvider = { apiClient },
        ioDispatcher = testSchedulerDispatcher,
        secretApiFactory = HelmSecretApiFactory.Default,
    )
}

private fun secretListJson(
    secrets: List<String>,
): String {
    return """
        {
          "apiVersion": "v1",
          "kind": "SecretList",
          "items": [${secrets.joinToString(",")}]
        }
    """.trimIndent()
}

private fun secretJson(
    secretName: String,
    releaseName: String,
    namespace: String,
    revision: Int,
    statusLabel: String,
    releaseData: ByteArray,
): String {
    return """
        {
          "apiVersion": "v1",
          "kind": "Secret",
          "metadata": {
            "name": "$secretName",
            "namespace": "$namespace",
            "labels": {
              "owner": "helm",
              "name": "$releaseName",
              "status": "$statusLabel",
              "version": "$revision"
            }
          },
          "type": "helm.sh/release.v1",
          "data": {
            "release": "${String(releaseData, Charsets.UTF_8)}"
          }
        }
    """.trimIndent()
}

private fun releaseDataField(
    name: String,
    namespace: String,
    revision: Int,
    status: Int,
    chartName: String,
    chartVersion: String,
    appVersion: String,
    description: String,
    manifest: String,
    deployedSeconds: Long,
): ByteArray {
    val decoded = DecodedHelmRelease(
        name = name,
        namespace = namespace,
        revision = revision,
        manifest = manifest,
        chartName = chartName,
        chartVersion = chartVersion,
        appVersion = appVersion,
        status = when (status) {
            1 -> "deployed"
            2 -> "uninstalled"
            3 -> "superseded"
            4 -> "failed"
            5 -> "uninstalling"
            6 -> "pending_install"
            7 -> "pending_upgrade"
            8 -> "pending_rollback"
            else -> "unknown"
        },
        updatedAt = deployedSeconds * 1_000L,
        description = description,
    )
    val encodedReleaseField = HelmReleaseSecretCodec.encodeRollback(
        secretName = "test-release-$revision",
        source = decoded,
        nextRevision = revision,
        rollbackTargetRevision = revision,
        rollbackTimestampMillis = deployedSeconds * 1_000L,
    )
    return Base64.getEncoder().encodeToString(encodedReleaseField).toByteArray(Charsets.UTF_8)
}

private fun extractReleaseField(jsonBody: String): String {
    val jsonObject = JsonParser.parseString(jsonBody).asJsonObject
    return jsonObject
        .getAsJsonObject("data")
        .get("release")
        .asString
}
