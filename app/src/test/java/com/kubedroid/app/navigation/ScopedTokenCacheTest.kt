package com.kubedroid.app.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScopedTokenCacheTest {

    @Test
    fun tokenAccessibleWithinTtlWindow() {
        var now = 1_000L
        val cache = ScopedTokenCache(
            ttlMs = 60_000L,
            currentTimeMs = { now },
            logger = {},
        )

        cache.onSessionStateChanged(true)
        cache.store("secret")
        now = 10_000L

        assertEquals("secret", cache.get())
    }

    @Test
    fun tokenNullAfterTtlExpires() {
        var now = 1_000L
        val cache = ScopedTokenCache(
            ttlMs = 1_000L,
            currentTimeMs = { now },
            logger = {},
        )

        cache.onSessionStateChanged(true)
        cache.store("secret")
        now = 2_500L

        assertNull(cache.get())
    }

    @Test
    fun tokenClearedWhenOnStopFires() {
        val cache = ScopedTokenCache(
            currentTimeMs = { 1_000L },
            logger = {},
        )

        cache.onSessionStateChanged(true)
        cache.store("secret")
        cache.onStop(TestLifecycleOwner())

        assertNull(cache.get())
    }

    @Test
    fun tokenClearedWhenNavigatingToUnauthenticatedRoute() {
        val cache = ScopedTokenCache(
            currentTimeMs = { 1_000L },
            logger = {},
        )

        cache.onSessionStateChanged(true)
        cache.store("secret")
        cache.onSessionStateChanged(false)

        assertNull(cache.get())
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = TestLifecycle
    }

    private object TestLifecycle : Lifecycle() {
        override fun addObserver(observer: LifecycleObserver) = Unit

        override fun removeObserver(observer: LifecycleObserver) = Unit

        override val currentState: State
            get() = State.STARTED
    }
}
