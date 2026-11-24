package ru.mars.mobile

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

/**
 * Ключ Yeta Platform для генерации картинок (§3 контракта волны 9).
 *
 * Почему отдельный класс, а не ещё один профиль в [LocalProviderPrefsStore]: профиль —
 * это «через какого провайдера отвечает **текстовый** чат», и картинки к нему не
 * относятся. Человек может чатиться с PlusVibe и при этом генерировать картинки на
 * Yeta — один выбор активного профиля на две разные задачи не хватает.
 *
 * Почему отдельный алиас AndroidKeyStore (`mars.local.image.key`), а не тот же, что у
 * провайдеров: шифровальный ключ провайдера переживает logout устройства, а ключ
 * картинок может быть удалён независимо. Общий алиас связал бы их судьбы — «забыть
 * ключ Yeta» стёрло бы заодно и рабочий ключ чата.
 *
 * Схема ровно та же, что у [LocalProviderPrefsStore]: AES/GCM, ключ в Keystore,
 * в SharedPreferences — только шифротекст и IV. `allowBackup=false` уже стоит в
 * манифесте, поэтому бэкап ключ не утащит.
 *
 * Хранилище синхронное и дешёвое: обращение идёт из инструмента один раз за картинку.
 */
class YetaImageKeyStore(private val context: Context) {

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Алиас шифровального ключа картинок. Не менять: существующие ключи станут нечитаемы. */
        const val KEY_ALIAS = "mars.local.image.key"

        /** Базовый адрес API: эндпоинт картинок = базовый + `/images/generations`. */
        const val BASE_URL = "https://platform.yeta.ai/v1"

        /** Модель по умолчанию: быстрая и уже проверенная вживую 30.10. */
        const val DEFAULT_MODEL = "seedream-5.0-flash"

        /** Модели картинок, которые отдаёт платформа (проверено 30.10). */
        val MODELS: List<String> = listOf(
            "seedream-5.0-pro",
            "seedream-5.0-flash",
            "seedream-5.0-lite",
            "seedream-4.5",
            "seedream-4.0",
            "seededit-3.0",
        )

        private const val PREFS = "mars-local-image"
        private const val KEY_API_KEY = "yeta_api_key"
        private const val KEY_IV = "yeta_api_key_iv"
        private const val KEY_MODEL = "yeta_image_model"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val keyStore: KeyStore? by lazy {
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }.getOrNull()
    }

    /** Есть ли ключ: инструмент по этому признаку решает, звать API или вернуть ошибку. */
    fun hasKey(): Boolean = apiKey() != null

    /** Ключ в открытом виде; null — не сохранён или расшифровать не удалось. */
    fun apiKey(): String? {
        val encoded = prefs.getString(KEY_API_KEY, null) ?: return null
        val encodedIv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(TAG_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
                )
            }
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clearApiKey()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ciphertext = cipher.doFinal(trimmed.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString(KEY_API_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).remove(KEY_IV).apply()
    }

    /** Модель картинок: по умолчанию — [DEFAULT_MODEL], если сохранённая неизвестна. */
    fun model(): String =
        prefs.getString(KEY_MODEL, null)?.takeIf { it in MODELS } ?: DEFAULT_MODEL

    fun saveModel(model: String) {
        if (model !in MODELS) return
        prefs.edit().putString(KEY_MODEL, model).apply()
    }

    /**
     * Ключ шифрования. Отдельный алиас от провайдерского и от серверного credential:
     * удаление одного не делает нечитаемыми остальные.
     */
    private fun secretKey(): SecretKey {
        val store = keyStore ?: error("AndroidKeyStore недоступен")
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(false)
                        .build()
                )
            }.generateKey()
        }
        return (store.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
}
