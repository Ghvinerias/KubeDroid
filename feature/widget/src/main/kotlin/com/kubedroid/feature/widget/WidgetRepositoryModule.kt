package com.kubedroid.feature.widget

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindWidgetRepository(impl: WidgetRepositoryImpl): WidgetRepository

    @Binds
    @Singleton
    abstract fun bindWidgetSyncCoordinator(impl: WidgetRepositoryImpl): WidgetSyncCoordinator

    @Binds
    @Singleton
    abstract fun bindWidgetAlertNotifier(impl: WidgetNotificationDispatcher): WidgetAlertNotifier
}
