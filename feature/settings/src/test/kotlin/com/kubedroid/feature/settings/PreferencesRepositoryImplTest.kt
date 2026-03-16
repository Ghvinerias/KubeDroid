package com.kubedroid.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kubedroid.feature.settings.domain.model.AppPreferences
import com.kubedroid.feature.settings.domain.model.AppTheme
import com.kubedroid.feature.settings.domain.model.LogBufferSize
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PreferencesRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var repository: PreferencesRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearDataStore()
        repository = PreferencesRepositoryImpl(context)
    }

    @After
    fun tearDown() {
        clearDataStore()
    }

    @Test
    fun test01_getPreferences_whenStoreIsEmpty_returnsDefaultPreferences() = runBlocking {
        val result = repository.getPreferences().first()

        assertEquals(AppPreferences(), result)
    }

    @Test
    fun test02_updatePreferences_persistsAndReturnsUpdatedValues() = runBlocking {
        val updated = AppPreferences(
            theme = AppTheme.OledBlack,
            defaultNamespace = "observability",
            logBufferSize = LogBufferSize.Size10000,
            metricsRefreshSeconds = 60,
            biometricLockEnabled = true,
            cacheEnabled = false,
        )

        repository.updatePreferences(updated)

        val persisted = repository.getPreferences().first()
        assertEquals(updated, persisted)
    }

    @Test
    fun test03_getPreferences_emitsUpdatedValueAfterWrite() = runBlocking {
        val updated = AppPreferences(theme = AppTheme.Dark)
        val nextEmissionDeferred = async {
            repository.getPreferences().drop(1).first()
        }

        repository.updatePreferences(updated)

        val nextEmission = withTimeout(3_000) { nextEmissionDeferred.await() }
        assertEquals(updated.theme, nextEmission.theme)
    }

    private fun clearDataStore() {
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            dataStoreDir.deleteRecursively()
        }
    }
}
