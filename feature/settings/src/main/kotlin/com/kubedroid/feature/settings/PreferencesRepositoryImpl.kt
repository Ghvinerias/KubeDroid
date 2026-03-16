package com.kubedroid.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kubedroid.feature.settings.domain.model.AppPreferences
import com.kubedroid.feature.settings.domain.model.AppTheme
import com.kubedroid.feature.settings.domain.model.LogBufferSize
import com.kubedroid.feature.settings.domain.repository.PreferencesRepository
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val UNKNOWN_APP_VERSION = "unknown"
private const val UNKNOWN_APP_BUILD = "unknown"

class PreferencesRepositoryImpl @Inject constructor(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
) : PreferencesRepository {

    override fun getPreferences(): Flow<AppPreferences> {
        return dataStore.data
            .catch { throwable ->
                if (throwable is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw throwable
                }
            }
            .map { preferences ->
                AppPreferences(
                    theme = preferences[Keys.theme]?.toAppTheme() ?: AppTheme.System,
                    defaultNamespace = preferences[Keys.defaultNamespace] ?: "default",
                    logBufferSize = preferences[Keys.logBufferSize]?.toLogBufferSize()
                        ?: LogBufferSize.Size5000,
                    metricsRefreshSeconds = preferences[Keys.metricsRefreshSeconds] ?: 15,
                    biometricLockEnabled = preferences[Keys.biometricLockEnabled] ?: false,
                    cacheEnabled = preferences[Keys.cacheEnabled] ?: true,
                )
            }
    }

    override suspend fun updatePreferences(preferences: AppPreferences) {
        dataStore.edit { mutablePreferences ->
            mutablePreferences[Keys.theme] = preferences.theme.name
            mutablePreferences[Keys.defaultNamespace] = preferences.defaultNamespace
            mutablePreferences[Keys.logBufferSize] = preferences.logBufferSize.lines
            mutablePreferences[Keys.metricsRefreshSeconds] = preferences.metricsRefreshSeconds
            mutablePreferences[Keys.biometricLockEnabled] = preferences.biometricLockEnabled
            mutablePreferences[Keys.cacheEnabled] = preferences.cacheEnabled
        }
    }

    override suspend fun clearCache() {
        context.databaseList().forEach { databaseName ->
            context.deleteDatabase(databaseName)
        }
    }

    override suspend fun getCacheSizeBytes(): Long {
        val databaseSize = context.databaseList()
            .sumOf { databaseName -> context.getDatabasePath(databaseName).fileSize() }
        val cacheDirSize = context.cacheDir.fileSize()
        val codeCacheDirSize = context.codeCacheDir.fileSize()
        return databaseSize + cacheDirSize + codeCacheDirSize
    }

    override fun getAppVersion(): String {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull() ?: return UNKNOWN_APP_VERSION
        return packageInfo.versionName ?: UNKNOWN_APP_VERSION
    }

    override fun getAppBuildNumber(): String {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull() ?: return UNKNOWN_APP_BUILD
        return packageInfo.longVersionCode.toString()
    }

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val defaultNamespace = stringPreferencesKey("default_namespace")
        val logBufferSize = intPreferencesKey("log_buffer_size")
        val metricsRefreshSeconds = intPreferencesKey("metrics_refresh_seconds")
        val biometricLockEnabled = booleanPreferencesKey("biometric_lock_enabled")
        val cacheEnabled = booleanPreferencesKey("cache_enabled")
    }
}

private fun File?.fileSize(): Long {
    if (this == null || !exists()) {
        return 0L
    }
    if (isFile) {
        return length()
    }
    return listFiles()?.sumOf { child -> child.fileSize() } ?: 0L
}

private fun String.toAppTheme(): AppTheme {
    return AppTheme.entries.firstOrNull { it.name == this } ?: AppTheme.System
}

private fun Int.toLogBufferSize(): LogBufferSize {
    return LogBufferSize.entries.firstOrNull { it.lines == this } ?: LogBufferSize.Size5000
}
