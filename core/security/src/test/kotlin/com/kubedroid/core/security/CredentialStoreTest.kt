package com.kubedroid.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.objenesis.ObjenesisStd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun test_putAndGetSecret_roundTripSucceeds() {
        val store = createStoreWithPreferences("credential_store_test_put_get_${System.nanoTime()}")

        store.putSecret("apiToken", "secret-value")

        assertEquals("secret-value", store.getSecret("apiToken"))
    }

    @Test
    fun test_removeSecret_deletesOnlyTargetKey() {
        val store = createStoreWithPreferences("credential_store_test_remove_${System.nanoTime()}")
        store.putSecret("keep", "value-1")
        store.putSecret("remove", "value-2")

        store.removeSecret("remove")

        assertEquals("value-1", store.getSecret("keep"))
        assertNull(store.getSecret("remove"))
    }

    @Test
    fun test_clear_removesAllKeys() {
        val store = createStoreWithPreferences("credential_store_test_clear_${System.nanoTime()}")
        store.putSecret("a", "1")
        store.putSecret("b", "2")

        store.clear()

        assertNull(store.getSecret("a"))
        assertNull(store.getSecret("b"))
    }

    private fun createStoreWithPreferences(fileName: String): CredentialStore {
        val sharedPreferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        val instance = ObjenesisStd().newInstance(CredentialStore::class.java)

        setField(instance, "appContext", context.applicationContext)
        setField(instance, "sharedPreferences", sharedPreferences)

        return instance
    }

    private fun setField(target: CredentialStore, fieldName: String, value: Any) {
        val field = CredentialStore::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
