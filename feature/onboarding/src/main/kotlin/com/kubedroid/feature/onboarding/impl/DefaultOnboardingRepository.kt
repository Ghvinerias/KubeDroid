package com.kubedroid.feature.onboarding.impl

import android.content.Context
import com.kubedroid.core.network.kubeconfig.KubeCluster
import com.kubedroid.core.network.kubeconfig.KubeConfig
import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import com.kubedroid.core.network.kubeconfig.KubeContext
import com.kubedroid.core.network.kubeconfig.KubeUser
import com.kubedroid.feature.onboarding.OnboardingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kubernetes.client.util.KubeConfig as ClientKubeConfig
import java.io.StringReader
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml

class DefaultOnboardingRepository @Inject constructor(
    private val kubeConfigRepository: KubeConfigRepository,
    @ApplicationContext private val context: Context,
) : OnboardingRepository {

    private val yaml = Yaml()

    override suspend fun isOnboardingComplete(): Boolean = withContext(Dispatchers.IO) {
        prefs().getBoolean(KEY_ONBOARDING_COMPLETE, false)
    }

    override suspend fun markOnboardingComplete() {
        withContext(Dispatchers.IO) {
            prefs().edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
        }
    }

    override suspend fun saveOnboardingKubeconfig(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = parseKubeConfig(content)
            kubeConfigRepository.save(config).getOrThrow()
        }
    }

    override suspend fun areCoachMarksComplete(): Boolean = withContext(Dispatchers.IO) {
        prefs().getBoolean(KEY_COACH_MARKS_COMPLETE, false)
    }

    override suspend fun markCoachMarksComplete() {
        withContext(Dispatchers.IO) {
            prefs().edit().putBoolean(KEY_COACH_MARKS_COMPLETE, true).apply()
        }
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun parseKubeConfig(raw: String): KubeConfig {
        if (raw.isBlank()) {
            error("Kubeconfig is empty")
        }

        ClientKubeConfig.loadKubeConfig(StringReader(raw))

        @Suppress("UNCHECKED_CAST")
        val root = yaml.load<Any?>(raw) as? Map<String, Any?> ?: emptyMap()

        val contexts = (root["contexts"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { entry ->
            val name = entry["name"] as? String ?: return@mapNotNull null
            val contextMap = entry["context"] as? Map<String, Any?> ?: return@mapNotNull null
            val cluster = contextMap["cluster"] as? String ?: return@mapNotNull null
            val user = contextMap["user"] as? String ?: return@mapNotNull null
            val namespace = contextMap["namespace"] as? String
            KubeContext(name = name, cluster = cluster, user = user, namespace = namespace)
        }

        val clusters = (root["clusters"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { entry ->
            val name = entry["name"] as? String ?: return@mapNotNull null
            val clusterMap = entry["cluster"] as? Map<String, Any?> ?: return@mapNotNull null
            val server = clusterMap["server"] as? String ?: return@mapNotNull null
            KubeCluster(name = name, server = server, raw = clusterMap)
        }

        val users = (root["users"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { entry ->
            val name = entry["name"] as? String ?: return@mapNotNull null
            val userMap = entry["user"] as? Map<String, Any?> ?: emptyMap()
            KubeUser(name = name, raw = userMap)
        }

        val currentContext = root["current-context"] as? String ?: contexts.firstOrNull()?.name

        return KubeConfig(
            contexts = contexts,
            clusters = clusters,
            users = users,
            currentContext = currentContext,
        )
    }

    private companion object {
        const val PREFS_NAME = "onboarding_preferences"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        const val KEY_COACH_MARKS_COMPLETE = "coach_marks_complete"
    }
}
