package com.example.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurePreferencesManager(context: Context) {
    private val sharedPrefs = context.getSharedPreferences("secure_manhwa_settings", Context.MODE_PRIVATE)
    
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "secure_manhwa_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    init {
        initKeyStoreKey()
    }

    private fun initKeyStoreKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                 .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                 .build()
                
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    @Synchronized
    fun encryptString(value: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val encryptedBytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            
            // Format: iv_base64:encrypted_base64
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            "$ivBase64:$encryptedBase64"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Synchronized
    fun decryptString(encryptedValue: String): String? {
        return try {
            val parts = encryptedValue.split(":")
            if (parts.size != 2) return null
            
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveSecureString(key: String, value: String) {
        val encrypted = encryptString(value)
        if (encrypted != null) {
            sharedPrefs.edit().putString(key, encrypted).apply()
        }
    }

    fun getSecureString(key: String, defaultValue: String): String {
        val encrypted = sharedPrefs.getString(key, null) ?: return defaultValue
        return decryptString(encrypted) ?: defaultValue
    }

    fun saveSecureLong(key: String, value: Long) {
        saveSecureString(key, value.toString())
    }

    fun getSecureLong(key: String, defaultValue: Long): Long {
        val valueStr = getSecureString(key, "")
        return valueStr.toLongOrNull() ?: defaultValue
    }

    fun saveSecureBoolean(key: String, value: Boolean) {
        saveSecureString(key, value.toString())
    }

    fun getSecureBoolean(key: String, defaultValue: Boolean): Boolean {
        val valueStr = getSecureString(key, "")
        return valueStr.toBooleanStrictOrNull() ?: defaultValue
    }
}
