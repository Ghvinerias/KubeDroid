package com.kubedroid.core.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockManager @Inject constructor() : DefaultLifecycleObserver {
    private var isUnlocked = false
    private var lastForegroundTime = 0L

    fun markUnlocked() {
        isUnlocked = true
        lastForegroundTime = System.currentTimeMillis()
    }

    fun markLocked() {
        isUnlocked = false
    }

    fun requiresUnlock(): Boolean = !isUnlocked

    override fun onStop(owner: LifecycleOwner) {
        lastForegroundTime = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val elapsed = System.currentTimeMillis() - lastForegroundTime
        if (elapsed > LOCK_TIMEOUT_MS) {
            markLocked()
        }
    }

    private companion object {
        const val LOCK_TIMEOUT_MS = 30_000L
    }
}
