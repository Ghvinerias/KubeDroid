package com.kubedroid.feature.settings.domain.repository

import com.kubedroid.feature.settings.domain.model.AppPreferences
import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    fun getPreferences(): Flow<AppPreferences>

    suspend fun updatePreferences(preferences: AppPreferences)

    suspend fun clearCache()

    suspend fun getCacheSizeBytes(): Long

    fun getAppVersion(): String

    fun getAppBuildNumber(): String
}
