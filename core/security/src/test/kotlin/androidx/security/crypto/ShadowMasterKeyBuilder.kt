package androidx.security.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@Implements(MasterKey.Builder::class)
class ShadowMasterKeyBuilder {
    @RealObject
    private lateinit var realBuilder: MasterKey.Builder

    private var alias: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS

    @Implementation
    protected fun __constructor__(context: Context) {
        alias = MasterKey.DEFAULT_MASTER_KEY_ALIAS
    }

    @Implementation
    protected fun __constructor__(context: Context, keyAlias: String) {
        alias = keyAlias
    }

    @Implementation
    protected fun setKeyGenParameterSpec(keyGenParameterSpec: KeyGenParameterSpec): MasterKey.Builder {
        return realBuilder
    }

    @Implementation
    protected fun setRequestStrongBoxBacked(requestStrongBoxBacked: Boolean): MasterKey.Builder {
        return realBuilder
    }

    @Implementation
    protected fun build(): MasterKey {
        return MasterKey(alias, Any())
    }
}
