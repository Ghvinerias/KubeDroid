package com.kubedroid.feature.onboarding

interface OnboardingRepository {
    suspend fun isOnboardingComplete(): Boolean

    suspend fun markOnboardingComplete()

    suspend fun saveOnboardingKubeconfig(content: String): Result<Unit>

    suspend fun areCoachMarksComplete(): Boolean

    suspend fun markCoachMarksComplete()
}
