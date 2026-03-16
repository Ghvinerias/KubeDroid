package com.kubedroid.feature.metrics.di

import com.kubedroid.feature.metrics.domain.repository.MetricsRepository
import com.kubedroid.feature.metrics.impl.MetricsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MetricsFeatureBindings {

    @Binds
    @Singleton
    abstract fun bindMetricsRepository(
        impl: MetricsRepositoryImpl,
    ): MetricsRepository
}
