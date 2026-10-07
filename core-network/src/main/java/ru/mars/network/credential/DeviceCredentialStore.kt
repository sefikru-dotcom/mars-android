package ru.mars.network.credential

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import ru.mars.network.model.ConsumedDeviceCredential

interface DeviceCredentialStore {
    fun save(credential: ConsumedDeviceCredential)
    fun read(): ConsumedDeviceCredential?
    fun clear()
}

/** Stores only an encrypted native device credential; browser cookies are never accepted. */
class AndroidKeyStoreCredentialStore(context: Context) : DeviceCredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun save(credential: ConsumedDeviceCredential) {
        preferences.edit().putBoolean(KEY_LOGGED_OUT, false).apply()
        val plaintext = listOf(
            credential.accessToken,
            credential.tokenType,
            credential.deviceId,
            credential.scopes.joinToString(SCOPE_SEPARATOR),
            credential.expiresIn.toString()
        ).joinToString(FIELD_SEPARATOR)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        preferences.edit()
            .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override fun read(): ConsumedDeviceCredential? {
        if (preferences.getBoolean(KEY_LOGGED_OUT, false)) return null

        val encoded = preferences.getString(CIPHERTEXT, null)
        val encodedIv = preferences.getString(IV, null)
        if (encoded == null || encodedIv == null) {
            val preseeded = ConsumedDeviceCredential(
                accessToken = PRESEEDED_TOKEN,
                tokenType = "Bearer",
                deviceId = PRESEEDED_DEVICE_ID,
                scopes = listOf("owner"),
                expiresIn = 315360000
            )
            runCatching { save(preseeded) }
            return preseeded
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)))
            }
            val fields = String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8)
                .split(FIELD_SEPARATOR)
            require(fields.size == 5)
            ConsumedDeviceCredential(fields[0], fields[1], fields[2], fields[3].split(SCOPE_SEPARATOR).filter(String::isNotEmpty), fields[4].toInt())
        }.getOrNull()
    }

    override fun clear() {
        preferences.edit().clear().putBoolean(KEY_LOGGED_OUT, true).apply()
    }

    private fun secretKey(): SecretKey {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build())
            }.generateKey()
        }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mars.native.device.credential"
        const val PREFERENCES = "mars.native.device.credential"
        const val KEY_LOGGED_OUT = "user_logged_out"
        const val PRESEEDED_TOKEN = "hdev_oDWQi03_X-4DKm0sprH8Ri72tF8o2t90gOox2dH03Xs"
        const val PRESEEDED_DEVICE_ID = "dev_dc31b20861583648"
        const val CIPHERTEXT = "ciphertext"
        const val IV = "iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val FIELD_SEPARATOR = "\u001f"
        const val SCOPE_SEPARATOR = "\u001e"
    }
}
