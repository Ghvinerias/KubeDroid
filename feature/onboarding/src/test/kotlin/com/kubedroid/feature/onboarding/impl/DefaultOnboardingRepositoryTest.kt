package com.kubedroid.feature.onboarding.impl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kubedroid.core.network.kubeconfig.KubeConfig
import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import com.kubedroid.core.network.kubeconfig.KubeContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultOnboardingRepositoryTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun test_isOnboardingComplete_firstRunReturnsFalse() = runTest {
        val repository = repository()

        val result = repository.isOnboardingComplete()

        assertFalse(result)
    }

    @Test
    fun test_markOnboardingComplete_marksAndPersistsCompletion() = runTest {
        val firstRepository = repository()
        assertFalse(firstRepository.isOnboardingComplete())

        firstRepository.markOnboardingComplete()

        val secondRepository = repository()
        assertTrue(firstRepository.isOnboardingComplete())
        assertTrue(secondRepository.isOnboardingComplete())
    }

    private fun repository(): DefaultOnboardingRepository {
        return DefaultOnboardingRepository(
            kubeConfigRepository = NoOpKubeConfigRepository,
            context = context,
        )
    }

    private object NoOpKubeConfigRepository : KubeConfigRepository {
        override suspend fun load(): Result<KubeConfig> = Result.failure(UnsupportedOperationException())

        override suspend fun save(config: KubeConfig): Result<Unit> = Result.success(Unit)

        override suspend fun delete(): Result<Unit> = Result.success(Unit)

        override suspend fun listContexts(): Result<List<KubeContext>> = Result.success(emptyList())

        override suspend fun setActiveContext(contextName: String): Result<Unit> = Result.success(Unit)
    }

    private companion object {
        const val PREFS_NAME = "onboarding_preferences"
    }
}
