package com.kubedroid.feature.events.di

import com.kubedroid.feature.events.domain.repository.EventsRepository
import com.kubedroid.feature.events.impl.EventsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EventsFeatureBindings {

    @Binds
    @Singleton
    abstract fun bindEventsRepository(
        impl: EventsRepositoryImpl,
    ): EventsRepository
}
