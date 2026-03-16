package com.kubedroid.feature.rbac.impl

import android.content.Context
import com.kubedroid.feature.rbac.domain.model.CanIResult
import com.kubedroid.feature.rbac.domain.model.RbacBinding
import com.kubedroid.feature.rbac.domain.model.RbacRole
import com.kubedroid.feature.rbac.domain.repository.RbacRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.AuthorizationV1Api
import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api
import io.kubernetes.client.openapi.models.V1PolicyRule
import io.kubernetes.client.openapi.models.V1ResourceAttributes
import io.kubernetes.client.openapi.models.V1SelfSubjectAccessReview
import io.kubernetes.client.openapi.models.V1SelfSubjectAccessReviewSpec
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class RbacRepositoryImpl(
    private val apiClientProvider: () -> ApiClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RbacRepository {

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        kubeConfigPath = File(context.filesDir, "kube/config").toPath(),
        ioDispatcher = Dispatchers.IO,
    )

    constructor(
        kubeConfigPath: Path,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        apiClientProvider = { buildApiClient(kubeConfigPath) },
        ioDispatcher = ioDispatcher,
    )

    override suspend fun listRoles(namespace: String): List<RbacRole> = withContext(ioDispatcher) {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isEmpty()) {
            emptyList()
        } else {
            val roleItems = runRbacListQuery {
                rbacApi().listNamespacedRole(normalizedNamespace).execute().items.orEmpty()
            }
            if (roleItems.isEmpty()) {
                emptyList()
            } else {
                val clusterAdminRoleRefs = listRoleBindings(normalizedNamespace)
                    .asSequence()
                    .map { it.roleRefName }
                    .filter { it == CLUSTER_ADMIN_ROLE_NAME }
                    .toSet()

                roleItems
                    .mapNotNull { role ->
                        val roleName = role.metadata?.name?.trim().orEmpty()
                        if (roleName.isEmpty()) {
                            null
                        } else {
                            role.toDomainRole(
                                namespace = normalizedNamespace,
                                hasClusterAdminBinding = roleName in clusterAdminRoleRefs,
                            )
                        }
                    }
                    .sortedBy { it.name }
            }
        }
    }

    override suspend fun listClusterRoles(): List<RbacRole> = withContext(ioDispatcher) {
        val clusterRoles = runRbacListQuery {
            rbacApi().listClusterRole().execute().items.orEmpty()
        }
        if (clusterRoles.isEmpty()) {
            emptyList()
        } else {
            val clusterAdminBound = listClusterRoleBindings()
                .any { it.roleRefName == CLUSTER_ADMIN_ROLE_NAME }

            clusterRoles
                .mapNotNull { clusterRole ->
                    val roleName = clusterRole.metadata?.name?.trim().orEmpty()
                    if (roleName.isEmpty()) {
                        null
                    } else {
                        clusterRole.toDomainRole(
                            namespace = null,
                            hasClusterAdminBinding = clusterAdminBound && roleName == CLUSTER_ADMIN_ROLE_NAME,
                        )
                    }
                }
                .sortedBy { it.name }
        }
    }

    override suspend fun listRoleBindings(namespace: String): List<RbacBinding> = withContext(ioDispatcher) {
        val normalizedNamespace = namespace.trim()
        if (normalizedNamespace.isEmpty()) {
            emptyList()
        } else {
            runRbacListQuery {
                rbacApi().listNamespacedRoleBinding(normalizedNamespace).execute().items.orEmpty()
            }.mapNotNull { binding ->
                val bindingName = binding.metadata?.name?.trim().orEmpty()
                if (bindingName.isEmpty()) {
                    null
                } else {
                    RbacBinding(
                        name = bindingName,
                        namespace = normalizedNamespace,
                        roleRefName = binding.roleRef?.name?.trim().orEmpty(),
                        subjectNames = binding.subjects
                            .orEmpty()
                            .mapNotNull { it.name?.trim() }
                            .filter { it.isNotEmpty() },
                    )
                }
            }.sortedBy { it.name }
        }
    }

    override suspend fun listClusterRoleBindings(): List<RbacBinding> = withContext(ioDispatcher) {
        runRbacListQuery {
            rbacApi().listClusterRoleBinding().execute().items.orEmpty()
        }.mapNotNull { binding ->
            val bindingName = binding.metadata?.name?.trim().orEmpty()
            if (bindingName.isEmpty()) {
                null
            } else {
                RbacBinding(
                    name = bindingName,
                    namespace = null,
                    roleRefName = binding.roleRef?.name?.trim().orEmpty(),
                    subjectNames = binding.subjects
                        .orEmpty()
                        .mapNotNull { it.name?.trim() }
                        .filter { it.isNotEmpty() },
                )
            }
        }.sortedBy { it.name }
    }

    override suspend fun canI(
        verb: String,
        resource: String,
        namespace: String?,
        subjectName: String,
    ): CanIResult = withContext(ioDispatcher) {
        val normalizedVerb = verb.trim()
        val normalizedResource = resource.trim()
        if (normalizedVerb.isEmpty() || normalizedResource.isEmpty()) {
            return@withContext CanIResult.Unknown
        }

        val review = V1SelfSubjectAccessReview()
            .spec(
                V1SelfSubjectAccessReviewSpec()
                    .resourceAttributes(
                        V1ResourceAttributes()
                            .verb(normalizedVerb)
                            .resource(normalizedResource)
                            .namespace(namespace?.trim()?.ifBlank { null }),
                    ),
            )

        runCatching {
            AuthorizationV1Api(apiClientProvider())
                .createSelfSubjectAccessReview(review)
                .execute()
                .status
                ?.allowed
        }.fold(
            onSuccess = { allowed ->
                when (allowed) {
                    true -> CanIResult.Allowed
                    false -> CanIResult.Denied
                    null -> CanIResult.Unknown
                }
            },
            onFailure = { CanIResult.Unknown },
        )
    }

    private fun rbacApi(): RbacAuthorizationV1Api = RbacAuthorizationV1Api(apiClientProvider())

    private inline fun <T> runRbacListQuery(block: () -> List<T>): List<T> {
        return try {
            block()
        } catch (apiException: ApiException) {
            if (apiException.code == 403) {
                emptyList()
            } else {
                throw apiException
            }
        }
    }

    private fun io.kubernetes.client.openapi.models.V1Role.toDomainRole(
        namespace: String,
        hasClusterAdminBinding: Boolean,
    ): RbacRole {
        val policyRuleStrings = rules.toRuleStrings()
        return RbacRole(
            name = metadata?.name.orEmpty(),
            namespace = namespace,
            rules = policyRuleStrings + dangerousClusterAdminFlag(hasClusterAdminBinding),
        )
    }

    private fun io.kubernetes.client.openapi.models.V1ClusterRole.toDomainRole(
        namespace: String?,
        hasClusterAdminBinding: Boolean,
    ): RbacRole {
        val policyRuleStrings = rules.toRuleStrings()
        return RbacRole(
            name = metadata?.name.orEmpty(),
            namespace = namespace,
            rules = policyRuleStrings + dangerousClusterAdminFlag(hasClusterAdminBinding),
        )
    }

    private fun List<V1PolicyRule>?.toRuleStrings(): List<String> {
        return this.orEmpty().map { rule ->
            val verbs = rule.verbs.orEmpty()
            val resources = rule.resources.orEmpty()
            val apiGroups = rule.apiGroups.orEmpty()
            val resourceNames = rule.resourceNames.orEmpty()
            val nonResourceUrls = rule.nonResourceURLs.orEmpty()

            val segments = buildList {
                add("verbs=${verbs.ifEmpty { listOf("<none>") }.joinToString(",")}")
                add("resources=${resources.ifEmpty { listOf("<none>") }.joinToString(",")}")
                if (apiGroups.isNotEmpty()) {
                    add("apiGroups=${apiGroups.joinToString(",")}")
                }
                if (resourceNames.isNotEmpty()) {
                    add("resourceNames=${resourceNames.joinToString(",")}")
                }
                if (nonResourceUrls.isNotEmpty()) {
                    add("nonResourceURLs=${nonResourceUrls.joinToString(",")}")
                }
                if (verbs.any { it == WILDCARD }) {
                    add(DANGEROUS_WILDCARD_VERBS)
                }
                if (resources.any { it == WILDCARD }) {
                    add(DANGEROUS_WILDCARD_RESOURCES)
                }
            }
            segments.joinToString(" | ")
        }
    }

    private fun dangerousClusterAdminFlag(hasClusterAdminBinding: Boolean): List<String> {
        return if (hasClusterAdminBinding) {
            listOf(DANGEROUS_CLUSTER_ADMIN_BINDING)
        } else {
            emptyList()
        }
    }

    companion object {
        private const val WILDCARD = "*"
        private const val CLUSTER_ADMIN_ROLE_NAME = "cluster-admin"
        private const val DANGEROUS_WILDCARD_VERBS = "DANGEROUS: wildcard verbs (*)"
        private const val DANGEROUS_WILDCARD_RESOURCES = "DANGEROUS: wildcard resources (*)"
        private const val DANGEROUS_CLUSTER_ADMIN_BINDING = "DANGEROUS: cluster-admin binding"

        private fun buildApiClient(kubeConfigPath: Path): ApiClient {
            if (!Files.exists(kubeConfigPath)) {
                throw IllegalStateException("Kubeconfig file not found at $kubeConfigPath")
            }
            val kubeConfigContents = String(Files.readAllBytes(kubeConfigPath), Charsets.UTF_8)
            val kubeConfig = KubeConfig.loadKubeConfig(StringReader(kubeConfigContents))
            return ClientBuilder.kubeconfig(kubeConfig).build()
        }
    }
}
