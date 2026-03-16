package com.kubedroid.app.navigation

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

data class TokenEntry(
    val token: String,
    val expiresAt: Long,
)

class ScopedTokenCache(
    private val ttlMs: Long = 15 * 60 * 1000L,
    private val currentTimeMs: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = { message ->
        Log.i(TAG, message)
    },
) : DefaultLifecycleObserver {

    private var entry: TokenEntry? = null
    private var sessionActive: Boolean = false

    fun store(token: String) {
        if (!sessionActive) {
            logger("Ignored token store because session is not active")
            return
        }
        entry = TokenEntry(token = token, expiresAt = currentTimeMs() + ttlMs)
    }

    fun get(): String? {
        if (!sessionActive) {
            clearInternal("inactive_session")
            return null
        }

        val current = entry ?: return null
        val now = currentTimeMs()
        if (now >= current.expiresAt) {
            clearInternal("ttl_expired")
            return null
        }

        entry = current.copy(expiresAt = now + ttlMs)
        return current.token
    }

    fun clear() {
        clearInternal("manual_clear")
    }

    fun onSessionStateChanged(isActive: Boolean) {
        if (sessionActive == isActive) return
        sessionActive = isActive
        if (!isActive) {
            clearInternal("unauthenticated_route")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        clearInternal("app_backgrounded")
    }

    private fun clearInternal(reason: String) {
        if (entry == null) return
        entry = null
        logger("Evicted scoped token. reason=$reason")
    }

    private companion object {
        const val TAG = "ScopedTokenCache"
    }
}
