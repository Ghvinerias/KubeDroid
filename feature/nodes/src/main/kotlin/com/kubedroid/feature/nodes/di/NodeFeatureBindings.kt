package com.kubedroid.feature.nodes.di

import com.kubedroid.feature.nodes.NodeRepository
import com.kubedroid.feature.nodes.impl.DefaultNodeRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NodeFeatureBindings {

    @Binds
    @Singleton
    abstract fun bindNodeRepository(
        impl: DefaultNodeRepository,
    ): NodeRepository
}
