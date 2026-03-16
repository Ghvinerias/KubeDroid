package com.kubedroid.feature.helm.di

import com.kubedroid.feature.helm.HelmRepository
import com.kubedroid.feature.helm.impl.HelmRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HelmFeatureBindings {

    @Binds
    @Singleton
    abstract fun bindHelmRepository(
        impl: HelmRepositoryImpl,
    ): HelmRepository
}
