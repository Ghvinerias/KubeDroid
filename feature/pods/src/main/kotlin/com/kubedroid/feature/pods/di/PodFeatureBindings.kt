package com.kubedroid.feature.pods.di

import com.kubedroid.feature.pods.exec.ExecRepository
import com.kubedroid.feature.pods.exec.impl.DefaultExecRepository
import com.kubedroid.feature.pods.logs.LogRepository
import com.kubedroid.feature.pods.logs.impl.DefaultLogRepository
import com.kubedroid.feature.pods.portforward.PortForwardRepository
import com.kubedroid.feature.pods.portforward.impl.DefaultPortForwardRepository
import com.kubedroid.feature.pods.resources.ResourceRepository
import com.kubedroid.feature.pods.resources.impl.DefaultResourceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PodFeatureBindings {

    @Binds
    @Singleton
    abstract fun bindExecRepository(
        impl: DefaultExecRepository,
    ): ExecRepository

    @Binds
    @Singleton
    abstract fun bindLogRepository(
        impl: DefaultLogRepository,
    ): LogRepository

    @Binds
    @Singleton
    abstract fun bindPortForwardRepository(
        impl: DefaultPortForwardRepository,
    ): PortForwardRepository

    @Binds
    @Singleton
    abstract fun bindResourceRepository(
        impl: DefaultResourceRepository,
    ): ResourceRepository
}
