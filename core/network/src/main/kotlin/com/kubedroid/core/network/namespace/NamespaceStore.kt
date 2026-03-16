package com.kubedroid.core.network.namespace

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DEFAULT_NAMESPACE = "default"

@Singleton
class NamespaceStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val key = stringPreferencesKey("selected_namespace")

    val selectedNamespace: Flow<String> = dataStore.data
        .map { it[key] ?: DEFAULT_NAMESPACE }

    suspend fun setNamespace(namespace: String) {
        val trimmedNamespace = namespace.trim()
        val normalizedNamespace = if (trimmedNamespace.isEmpty()) {
            DEFAULT_NAMESPACE
        } else {
            trimmedNamespace
        }
        dataStore.edit { preferences ->
            preferences[key] = normalizedNamespace
        }
    }
}
