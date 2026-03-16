package com.kubedroid.feature.deployments.di

import com.kubedroid.feature.deployments.DeploymentRepository
import com.kubedroid.feature.deployments.impl.DefaultDeploymentRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DeploymentFeatureBindings {

    @Binds
    @Singleton
    abstract fun bindDeploymentRepository(
        impl: DefaultDeploymentRepository,
    ): DeploymentRepository
}
