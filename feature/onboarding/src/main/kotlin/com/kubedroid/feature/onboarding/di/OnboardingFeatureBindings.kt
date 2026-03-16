package com.kubedroid.feature.onboarding.di

import com.kubedroid.feature.onboarding.OnboardingRepository
import com.kubedroid.feature.onboarding.impl.DefaultOnboardingRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingFeatureBindings {

    @Binds
    @Singleton
    abstract fun bindOnboardingRepository(
        impl: DefaultOnboardingRepository,
    ): OnboardingRepository
}
