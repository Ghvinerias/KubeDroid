package com.kubedroid.core.network.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.kubedroid.core.database.cache.CacheDatabase
import com.kubedroid.core.database.cache.CacheRepository
import com.kubedroid.core.database.cache.CacheRepositoryImpl
import com.kubedroid.core.network.connection.ClusterConnectionRepository
import com.kubedroid.core.network.config.ConfigMapRepository
import com.kubedroid.core.network.config.ConfigMapRepositoryImpl
import com.kubedroid.core.network.config.SecretRepository
import com.kubedroid.core.network.config.SecretRepositoryImpl
import com.kubedroid.core.network.deployments.DeploymentRepository
import com.kubedroid.core.network.deployments.DeploymentRepositoryImpl
import com.kubedroid.core.network.exec.ExecSessionFactory
import com.kubedroid.core.network.exec.ExecSessionFactoryImpl
import com.kubedroid.core.network.impl.ClusterConnectionRepositoryImpl
import com.kubedroid.core.network.impl.KubeConfigRepositoryImpl
import com.kubedroid.core.network.kubeconfig.KubeConfigRepository
import com.kubedroid.core.network.logs.LogRepository
import com.kubedroid.core.network.logs.LogStreamRepository
import com.kubedroid.core.network.logs.LogStreamRepositoryImpl
import com.kubedroid.core.network.nodes.NodeRepository
import com.kubedroid.core.network.nodes.NodeRepositoryImpl
import com.kubedroid.core.network.networking.NetworkRepository
import com.kubedroid.core.network.networking.NetworkRepositoryImpl
import com.kubedroid.core.network.namespace.NamespaceStore
import com.kubedroid.core.network.pods.NamespaceRepository
import com.kubedroid.core.network.pods.NamespaceRepositoryImpl
import com.kubedroid.core.network.pods.PodRepository
import com.kubedroid.core.network.pods.PodRepositoryImpl
import com.kubedroid.core.network.resources.ResourceDetailRepository
import com.kubedroid.core.network.resources.ResourceDetailRepositoryImpl
import com.kubedroid.core.network.storage.PvRepository
import com.kubedroid.core.network.storage.PvRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkRepositoryModule {

    @Provides
    @Singleton
    fun provideAppPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("app_preferences") },
    )

    @Provides
    @Singleton
    fun provideCacheDatabase(
        @ApplicationContext context: Context,
    ): CacheDatabase {
        return Room.databaseBuilder(
            context,
            CacheDatabase::class.java,
            "kubedroid-cache.db",
        ).fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    @Singleton
    fun provideCacheRepository(
        cacheDatabase: CacheDatabase,
    ): CacheRepository = CacheRepositoryImpl(cacheDatabase)

    @Provides
    @Singleton
    fun provideKubeConfigRepository(
        @ApplicationContext context: Context,
    ): KubeConfigRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return KubeConfigRepositoryImpl(kubeConfigPath = kubeConfigPath)
    }

    @Provides
    @Singleton
    fun provideClusterConnectionRepository(
        @ApplicationContext context: Context,
    ): ClusterConnectionRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return ClusterConnectionRepositoryImpl(kubeConfigPath = kubeConfigPath)
    }

    @Provides
    @Singleton
    fun providePodRepository(
        @ApplicationContext context: Context,
        cacheRepository: CacheRepository,
    ): PodRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return PodRepositoryImpl(
            kubeConfigPath = kubeConfigPath,
            cacheRepository = cacheRepository,
        )
    }

    @Provides
    @Singleton
    fun provideNamespaceRepository(
        @ApplicationContext context: Context,
    ): NamespaceRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return NamespaceRepositoryImpl(kubeConfigPath = kubeConfigPath)
    }

    @Provides
    @Singleton
    fun provideNamespaceStore(
        dataStore: DataStore<Preferences>,
    ): NamespaceStore = NamespaceStore(dataStore = dataStore)

    @Provides
    @Singleton
    fun provideDeploymentRepository(
        @ApplicationContext context: Context,
        cacheRepository: CacheRepository,
    ): DeploymentRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return DeploymentRepositoryImpl(
            kubeConfigPath = kubeConfigPath,
            cacheRepository = cacheRepository,
        )
    }

    @Provides
    @Singleton
    fun provideConfigMapRepository(
        @ApplicationContext context: Context,
    ): ConfigMapRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return ConfigMapRepositoryImpl(kubeConfigPath = kubeConfigPath)
    }

    @Provides
    @Singleton
    fun provideSecretRepository(
        @ApplicationContext context: Context,
    ): SecretRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return SecretRepositoryImpl(kubeConfigPath = kubeConfigPath)
    }

    @Provides
    @Singleton
    fun provideNodeRepository(
        @ApplicationContext context: Context,
        cacheRepository: CacheRepository,
    ): NodeRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return NodeRepositoryImpl(
            kubeConfigPath = kubeConfigPath,
            cacheRepository = cacheRepository,
        )
    }

    @Provides
    @Singleton
    fun provideResourceDetailRepository(
        @ApplicationContext context: Context,
    ): ResourceDetailRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return ResourceDetailRepositoryImpl(kubeConfigPath = kubeConfigPath)
    }

    @Provides
    @Singleton
    fun providePvRepository(
        @ApplicationContext context: Context,
    ): PvRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return PvRepositoryImpl(kubeConfigPath = kubeConfigPath)
    }

    @Provides
    @Singleton
    fun provideNetworkRepository(
        @ApplicationContext context: Context,
    ): NetworkRepository {
        val kubeConfigPath = File(context.filesDir, "kube/config").toPath()
        return NetworkRepositoryImpl(kubeConfigPath = kubeConfigPath)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    fun provideLogStreamRepositoryImpl(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): LogStreamRepositoryImpl = LogStreamRepositoryImpl(
        okHttpClient = okHttpClient,
        kubeConfigPath = File(context.filesDir, "kube/config").toPath(),
    )

    @Provides
    @Singleton
    fun provideLogRepository(
        impl: LogStreamRepositoryImpl,
    ): LogRepository = impl

    @Provides
    @Singleton
    fun provideLogStreamRepository(
        impl: LogStreamRepositoryImpl,
    ): LogStreamRepository = impl

    @Provides
    @Singleton
    fun provideExecSessionFactory(
        @ApplicationContext context: Context,
    ): ExecSessionFactory = ExecSessionFactoryImpl(
        kubeConfigPath = File(context.filesDir, "kube/config").toPath(),
    )
}
