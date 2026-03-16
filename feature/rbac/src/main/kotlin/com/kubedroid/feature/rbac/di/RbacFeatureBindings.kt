package com.kubedroid.feature.rbac.di

import com.kubedroid.feature.rbac.domain.repository.RbacRepository
import com.kubedroid.feature.rbac.impl.RbacRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RbacFeatureBindings {

    @Binds
    @Singleton
    abstract fun bindRbacRepository(
        impl: RbacRepositoryImpl,
    ): RbacRepository
}
