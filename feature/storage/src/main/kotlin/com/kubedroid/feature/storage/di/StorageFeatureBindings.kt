package com.kubedroid.feature.storage.di

import com.kubedroid.feature.storage.StorageRepository
import com.kubedroid.feature.storage.impl.DefaultStorageRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageFeatureBindings {

    @Binds
    @Singleton
    abstract fun bindStorageRepository(
        impl: DefaultStorageRepository,
    ): StorageRepository
}
