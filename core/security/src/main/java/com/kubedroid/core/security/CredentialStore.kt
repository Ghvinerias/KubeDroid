/*
 * Threat model:
 * - Protects credentials at rest against filesystem extraction by encrypting values.
 * - Uses Android Keystore-backed AES-256-GCM keys; prefers StrongBox-backed hardware key storage.
 * - Does not protect plaintext while in process memory after retrieval.
 */
package com.kubedroid.core.security

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException

class CredentialStore(
    context: Context,
    preferenceFileName: String = DEFAULT_PREFS_FILE,
) {
    private val appContext = context.applicationContext
    private val sharedPreferences: SharedPreferences =
        createEncryptedSharedPreferences(preferenceFileName)

    // Threat model: persist only encrypted-at-rest values to reduce impact of local storage extraction.
    fun putSecret(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    // Threat model: decrypts only on demand and avoids caching decrypted values in this class.
    fun getSecret(key: String): String? = sharedPreferences.getString(key, null)

    // Threat model: removes stale secrets so compromised backups contain less sensitive material.
    fun removeSecret(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }

    // Threat model: emergency wipe for all persisted secrets in this preference file.
    fun clear() {
        sharedPreferences.edit().clear().apply()
    }

    // Threat model: centralizes encrypted preference creation so key policy cannot drift by callsite.
    private fun createEncryptedSharedPreferences(preferenceFileName: String): SharedPreferences {
        val masterKey = createMasterKeyWithStrongBoxFallback()
        return EncryptedSharedPreferences.create(
            appContext,
            preferenceFileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // Threat model: attempts hardware-backed key storage first; downgrades safely if hardware is unavailable.
    private fun createMasterKeyWithStrongBoxFallback(): MasterKey {
        return if (isStrongBoxSupported()) {
            runCatching { buildMasterKey(strongBoxBacked = true) }
                .getOrElse { buildMasterKey(strongBoxBacked = false) }
        } else {
            buildMasterKey(strongBoxBacked = false)
        }
    }

    // Threat model: explicit key policy (AES-256-GCM) limits accidental use of weaker defaults.
    private fun buildMasterKey(strongBoxBacked: Boolean): MasterKey {
        return try {
            val spec = buildKeyGenParameterSpec(strongBoxBacked)
            MasterKey.Builder(appContext)
                .setKeyGenParameterSpec(spec)
                .build()
        } catch (securityException: GeneralSecurityException) {
            if (strongBoxBacked) {
                val fallbackSpec = buildKeyGenParameterSpec(strongBoxBacked = false)
                MasterKey.Builder(appContext)
                    .setKeyGenParameterSpec(fallbackSpec)
                    .build()
            } else {
                throw securityException
            }
        }
    }

    // Threat model: ensures generated Keystore key supports authenticated encryption semantics.
    private fun buildKeyGenParameterSpec(strongBoxBacked: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (strongBoxBacked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    // Threat model: checks device capability before requesting StrongBox-backed key material.
    private fun isStrongBoxSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    private companion object {
        const val DEFAULT_PREFS_FILE = "secure_credentials"
        const val MASTER_KEY_ALIAS = "_kubedroid_master_key_"
    }
}
