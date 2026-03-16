package androidx.security.crypto

import android.content.Context
import android.content.SharedPreferences
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(EncryptedSharedPreferences::class)
class ShadowEncryptedSharedPreferences {
    companion object {
        @Implementation
        @JvmStatic
        protected fun create(
            context: Context,
            fileName: String,
            masterKey: MasterKey,
            prefKeyEncryptionScheme: EncryptedSharedPreferences.PrefKeyEncryptionScheme,
            prefValueEncryptionScheme: EncryptedSharedPreferences.PrefValueEncryptionScheme,
        ): SharedPreferences {
            return context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
    }
}
