package com.kubedroid.feature.crd.di

import com.kubedroid.feature.crd.CrdRepository
import com.kubedroid.feature.crd.FavouriteCrdRepository
import com.kubedroid.feature.crd.impl.CrdRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CrdFeatureBindings {

    @Binds
    @Singleton
    abstract fun bindCrdRepository(
        impl: CrdRepositoryImpl,
    ): CrdRepository

    @Binds
    @Singleton
    abstract fun bindFavouriteCrdRepository(
        impl: CrdRepositoryImpl,
    ): FavouriteCrdRepository
}
