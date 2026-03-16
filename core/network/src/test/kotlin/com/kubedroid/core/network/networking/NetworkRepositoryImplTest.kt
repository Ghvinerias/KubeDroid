package com.kubedroid.core.network.networking

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.models.V1HTTPIngressPath
import io.kubernetes.client.openapi.models.V1HTTPIngressRuleValue
import io.kubernetes.client.openapi.models.V1Ingress
import io.kubernetes.client.openapi.models.V1IngressBackend
import io.kubernetes.client.openapi.models.V1IngressRule
import io.kubernetes.client.openapi.models.V1IngressServiceBackend
import io.kubernetes.client.openapi.models.V1IngressSpec
import io.kubernetes.client.openapi.models.V1IngressTLS
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1ServiceBackendPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkRepositoryImplTest {

    @Test
    fun test_ingressMapping_includesDefaultBackendBucketAndStableTlsSorting() {
        val ingress = v1Ingress(
            name = "edge",
            namespace = "default",
            ingressClass = "nginx",
            rules = listOf(
                ingressRule(
                    host = "app.example.com",
                    paths = listOf(
                        ingressPath(path = "/api", service = "api", port = "80"),
                    ),
                ),
            ),
            defaultBackendService = "fallback",
            defaultBackendPort = "8080",
            tls = listOf(
                ingressTls(secret = "b-secret", hosts = listOf("b.example.com", "a.example.com")),
                ingressTls(secret = "a-secret", hosts = emptyList()),
            ),
        )

        val mapped = ingress.toDomainIngress("default")

        assertEquals("edge", mapped.name)
        assertEquals("nginx", mapped.ingressClass)
        assertEquals(2, mapped.rules.size)
        assertEquals("app.example.com", mapped.rules[0].host)
        assertEquals("/api", mapped.rules[0].paths.single().path)
        assertEquals(null, mapped.rules[1].host)
        val defaultPath = mapped.rules[1].paths.single()
        assertEquals("fallback", defaultPath.serviceName)
        assertEquals("8080", defaultPath.servicePort)
        assertEquals("DefaultBackend", defaultPath.pathType)
        assertEquals("a-secret", mapped.tls[0].secretName)
        assertTrue(mapped.tls[0].hosts.isEmpty())
        assertEquals(listOf("a.example.com", "b.example.com"), mapped.tls[1].hosts)
    }

    @Test
    fun test_tlsCoverage_exactWildcardAndNoHostEntries() {
        val ingress = Ingress(
            name = "edge",
            namespace = "default",
            ingressClass = "nginx",
            rules = emptyList(),
            tls = listOf(
                IngressTls(hosts = listOf("*.example.com"), secretName = "wild"),
                IngressTls(hosts = emptyList(), secretName = "global"),
            ),
        )

        assertTrue(ingress.isTlsCoveredForHost("api.example.com"))
        assertTrue(ingress.isTlsCoveredForHost("anything.internal"))
        assertTrue(ingress.isTlsCoveredForHost(null))

        val exactOnly = ingress.copy(tls = listOf(IngressTls(hosts = listOf("app.example.com"), secretName = "exact")))
        assertTrue(exactOnly.isTlsCoveredForHost("app.example.com"))
        assertFalse(exactOnly.isTlsCoveredForHost("other.example.com"))
        assertFalse(exactOnly.isTlsCoveredForHost(null))
    }

    @Test
    fun test_getServiceUrl_prefersHttpsWhenTlsCoversHost() = runTest {
        val repository = repositoryWithIngresses(
            ingress(
                name = "edge",
                ingressClass = "nginx",
                rules = listOf(
                    IngressRule(
                        host = "app.example.com",
                        paths = listOf(IngressPath(path = "api", pathType = "Prefix", serviceName = "svc", servicePort = "80")),
                    ),
                ),
                tls = listOf(IngressTls(hosts = listOf("app.example.com"), secretName = "tls-secret")),
            ),
        )

        val result = repository.getServiceUrl(service = "svc", namespace = "default")

        assertTrue(result.isSuccess)
        assertEquals("https://app.example.com/api", result.getOrThrow())
    }

    @Test
    fun test_getServiceUrl_usesHttpWhenTlsDoesNotCoverHost() = runTest {
        val repository = repositoryWithIngresses(
            ingress(
                name = "edge",
                ingressClass = "nginx",
                rules = listOf(
                    IngressRule(
                        host = "app.example.com",
                        paths = listOf(IngressPath(path = "/api", pathType = "Prefix", serviceName = "svc", servicePort = "80")),
                    ),
                ),
                tls = listOf(IngressTls(hosts = listOf("other.example.com"), secretName = "tls-secret")),
            ),
        )

        val result = repository.getServiceUrl(service = "svc", namespace = "default")

        assertTrue(result.isSuccess)
        assertEquals("http://app.example.com/api", result.getOrThrow())
    }

    @Test
    fun test_getServiceUrl_multipleIngressClasses_returnsAmbiguousFailure() = runTest {
        val repository = repositoryWithIngresses(
            ingress(
                name = "edge-nginx",
                ingressClass = "nginx",
                rules = listOf(
                    IngressRule(
                        host = "a.example.com",
                        paths = listOf(IngressPath(path = "/", pathType = "Prefix", serviceName = "svc", servicePort = "80")),
                    ),
                ),
            ),
            ingress(
                name = "edge-traefik",
                ingressClass = "traefik",
                rules = listOf(
                    IngressRule(
                        host = "b.example.com",
                        paths = listOf(IngressPath(path = "/", pathType = "Prefix", serviceName = "svc", servicePort = "80")),
                    ),
                ),
            ),
        )

        val result = repository.getServiceUrl(service = "svc", namespace = "default")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("multiple ingress classes") == true)
    }

    @Test
    fun test_getServiceUrl_noRoute_returnsFailure() = runTest {
        val repository = repositoryWithIngresses(
            ingress(
                name = "edge",
                ingressClass = "nginx",
                rules = listOf(
                    IngressRule(
                        host = "app.example.com",
                        paths = listOf(IngressPath(path = "/", pathType = "Prefix", serviceName = "other", servicePort = "80")),
                    ),
                ),
            ),
        )

        val result = repository.getServiceUrl(service = "svc", namespace = "default")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No ingress route matches service") == true)
    }

    @Test
    fun test_getServiceUrl_defaultBackendWithoutHost_returnsFailure() = runTest {
        val repository = repositoryWithIngresses(
            ingress(
                name = "edge",
                ingressClass = "nginx",
                rules = listOf(
                    IngressRule(
                        host = null,
                        paths = listOf(
                            IngressPath(
                                path = null,
                                pathType = "DefaultBackend",
                                serviceName = "svc",
                                servicePort = "80",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = repository.getServiceUrl(service = "svc", namespace = "default")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("default backend") == true)
    }

    @Test
    fun test_getServiceUrl_prefersTlsCandidateOverNonTlsCandidate() = runTest {
        val repository = repositoryWithIngresses(
            ingress(
                name = "edge-http",
                ingressClass = "nginx",
                rules = listOf(
                    IngressRule(
                        host = "app.example.com",
                        paths = listOf(
                            IngressPath(
                                path = "/svc",
                                pathType = "Prefix",
                                serviceName = "svc",
                                servicePort = "80",
                            ),
                        ),
                    ),
                ),
                tls = emptyList(),
            ),
            ingress(
                name = "edge-https",
                ingressClass = "nginx",
                rules = listOf(
                    IngressRule(
                        host = "app.example.com",
                        paths = listOf(
                            IngressPath(
                                path = "/svc",
                                pathType = "Prefix",
                                serviceName = "svc",
                                servicePort = "443",
                            ),
                        ),
                    ),
                ),
                tls = listOf(IngressTls(hosts = listOf("app.example.com"), secretName = "tls-secret")),
            ),
        )

        val result = repository.getServiceUrl(service = "svc", namespace = "default")

        assertTrue(result.isSuccess)
        assertEquals("https://app.example.com/svc", result.getOrThrow())
    }

    @Test
    fun test_listIngresses_blankNamespace_returnsIllegalArgumentFailure() = runTest {
        val repository = repositoryWithIngresses()

        val result = repository.listIngresses(namespace = "  ")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun test_listIngresses_returnsNamesSortedAscending() = runTest {
        val repository = repositoryWithIngresses(
            ingress(name = "zeta", ingressClass = "nginx", rules = emptyList()),
            ingress(name = "alpha", ingressClass = "nginx", rules = emptyList()),
        )

        val result = repository.listIngresses(namespace = "default")

        assertTrue(result.isSuccess)
        assertEquals(listOf("alpha", "zeta"), result.getOrThrow().map { it.name })
    }

    @Test
    fun test_listNetworkPolicies_returnsNamesSortedAscending() = runTest {
        val repository = repositoryWithIngresses(
            policies = listOf(
                networkPolicy(name = "z-policy"),
                networkPolicy(name = "a-policy"),
            ),
        )

        val result = repository.listNetworkPolicies(namespace = "default")

        assertTrue(result.isSuccess)
        assertEquals(listOf("a-policy", "z-policy"), result.getOrThrow().map { it.name })
    }

    private fun repositoryWithIngresses(
        vararg ingresses: Ingress,
        policies: List<NetworkPolicy> = emptyList(),
    ): NetworkRepositoryImpl {
        return NetworkRepositoryImpl(
            apiClientProvider = { ApiClient() },
            ioDispatcher = UnconfinedTestDispatcher(),
            networkApiFactory = NetworkApiFactory {
                FakeNetworkApi(
                    ingressSnapshot = IngressListSnapshot(
                        ingresses = ingresses.toList(),
                        resourceVersion = "1",
                    ),
                    networkPolicies = policies,
                )
            },
        )
    }
}

private class FakeNetworkApi(
    private val ingressSnapshot: IngressListSnapshot = IngressListSnapshot(emptyList(), "1"),
    private val networkPolicies: List<NetworkPolicy> = emptyList(),
) : NetworkApi {
    override fun listIngressesSnapshot(namespace: String): IngressListSnapshot = ingressSnapshot

    override fun openIngressWatch(namespace: String, resourceVersion: String?): IngressWatchSession {
        error("Not used by these tests")
    }

    override fun listNetworkPolicies(namespace: String): List<NetworkPolicy> = networkPolicies
}

private fun ingress(
    name: String,
    ingressClass: String?,
    rules: List<IngressRule>,
    tls: List<IngressTls> = emptyList(),
): Ingress {
    return Ingress(
        name = name,
        namespace = "default",
        ingressClass = ingressClass,
        rules = rules,
        tls = tls,
    )
}

private fun networkPolicy(
    name: String,
): NetworkPolicy {
    return NetworkPolicy(
        name = name,
        namespace = "default",
        podSelector = emptyMap(),
        ingressRules = emptyList(),
        egressRules = emptyList(),
    )
}

private fun v1Ingress(
    name: String,
    namespace: String,
    ingressClass: String?,
    rules: List<V1IngressRule>,
    defaultBackendService: String? = null,
    defaultBackendPort: String? = null,
    tls: List<V1IngressTLS> = emptyList(),
): V1Ingress {
    val spec = V1IngressSpec()
        .ingressClassName(ingressClass)
        .rules(rules)
        .tls(tls)

    if (defaultBackendService != null) {
        val backendPort = V1ServiceBackendPort()
        if (defaultBackendPort?.toIntOrNull() != null) {
            backendPort.number(defaultBackendPort.toInt())
        } else {
            backendPort.name(defaultBackendPort)
        }
        spec.defaultBackend(
            V1IngressBackend().service(
                V1IngressServiceBackend()
                    .name(defaultBackendService)
                    .port(backendPort),
            ),
        )
    }

    return V1Ingress()
        .metadata(V1ObjectMeta().name(name).namespace(namespace))
        .spec(spec)
}

private fun ingressRule(
    host: String?,
    paths: List<V1HTTPIngressPath>,
): V1IngressRule {
    return V1IngressRule()
        .host(host)
        .http(V1HTTPIngressRuleValue().paths(paths))
}

private fun ingressPath(
    path: String?,
    service: String,
    port: String,
): V1HTTPIngressPath {
    val servicePort = V1ServiceBackendPort()
    if (port.toIntOrNull() != null) {
        servicePort.number(port.toInt())
    } else {
        servicePort.name(port)
    }
    return V1HTTPIngressPath()
        .path(path)
        .pathType("Prefix")
        .backend(
            V1IngressBackend().service(
                V1IngressServiceBackend()
                    .name(service)
                    .port(servicePort),
            ),
        )
}

private fun ingressTls(
    secret: String,
    hosts: List<String>,
): V1IngressTLS {
    return V1IngressTLS()
        .secretName(secret)
        .hosts(hosts)
}
