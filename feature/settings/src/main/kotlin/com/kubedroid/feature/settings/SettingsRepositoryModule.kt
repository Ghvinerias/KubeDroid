package com.kubedroid.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import com.kubedroid.feature.settings.domain.repository.PreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsRepositoryModule {

    @Provides
    @Singleton
    fun provideMultiClusterRepository(
        kubeConfigRepository: KubeConfigRepository,
        @ApplicationContext context: Context,
    ): MultiClusterRepository {
        return MultiClusterRepositoryImpl(
            kubeConfigRepository = kubeConfigRepository,
            kubeConfigPath = File(context.filesDir, "kube/config").toPath(),
        )
    }

    @Provides
    @Singleton
    fun providePreferencesRepository(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>,
    ): PreferencesRepository {
        return PreferencesRepositoryImpl(
            context = context,
            dataStore = dataStore,
        )
    }
}
