package ru.mars.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ru.mars.network.local.LocalProviderProfile
import ru.mars.network.local.LocalProviderState
import ru.mars.network.local.LocalProviderStore
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Профили прямого провайдера: открытые параметры в SharedPreferences, ключ API — в Keystore.
 *
 * Почему ключ отдельно от профиля: профиль читается экраном настроек и уходит в
 * диагностику/экспорт, ключ не должен попадать туда ни при каких обстоятельствах.
 * Схема ровно та же, что у серверного device-credential Марса
 * ([ru.mars.network.credential.AndroidKeyStoreCredentialStore]): AES/GCM в AndroidKeyStore,
 * без права чтения из бэкапа (allowBackup=false уже стоит в манифесте).
 *
 * Хранилище намеренно синхронное и дешёвое: профилей единицы, вызов идёт с экрана,
 * а не из горячего цикла ответа.
 */
class LocalProviderPrefsStore(private val context: Context) : LocalProviderStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val keyStore: KeyStore? by lazy {
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }.getOrNull()
    }

    override fun state(): LocalProviderState {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return LocalProviderState()
        val profiles = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val baseUrl = item.optString("baseUrl")
                val model = item.optString("model")
                LocalProviderProfile(
                    id = id,
                    title = item.optString("title", model),
                    baseUrl = baseUrl,
                    model = model,
                    temperature = if (item.has("temperature")) item.optDouble("temperature") else null,
                    maxTokens = if (item.has("maxTokens")) item.optInt("maxTokens") else null,
                    contextWindow = if (item.has("contextWindow")) item.optInt("contextWindow") else null,
                    reasoningEffort = item.optString("reasoningEffort").takeIf { it.isNotBlank() },
                )
            }
        }.getOrElse { emptyList() }
        val activeId = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> profiles.any { it.id == id } }
        return LocalProviderState(profiles = profiles, activeId = activeId)
    }

    override fun saveProfile(profile: LocalProviderProfile) {
        val current = state()
        val merged = current.profiles.filterNot { it.id == profile.id } + profile
        persistProfiles(merged)
        // Первый профиль становится активным сам: иначе человек настроил провайдера,
        // а чат продолжает говорить «не настроено» — и это выглядит как поломка.
        if (state().activeId == null) setActive(profile.id)
    }

    override fun removeProfile(id: String) {
        persistProfiles(state().profiles.filterNot { it.id == id })
        clearApiKey(id)
        if (prefs.getString(KEY_ACTIVE, null) == id) {
            val remaining = state().profiles.firstOrNull()?.id
            prefs.edit().apply {
                if (remaining == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, remaining)
            }.apply()
        }
    }

    override fun setActive(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
        }.apply()
    }

    override fun apiKey(profileId: String): String? {
        val encoded = prefs.getString(KEY_KEY_PREFIX + profileId, null) ?: return null
        val encodedIv = prefs.getString(KEY_IV_PREFIX + profileId, null) ?: return null
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

    override fun saveApiKey(profileId: String, key: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ciphertext = cipher.doFinal(key.trim().toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString(KEY_KEY_PREFIX + profileId, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV_PREFIX + profileId, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override fun clearApiKey(profileId: String) {
        prefs.edit().remove(KEY_KEY_PREFIX + profileId).remove(KEY_IV_PREFIX + profileId).apply()
    }

    private fun persistProfiles(profiles: List<LocalProviderProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("title", profile.title)
                    put("baseUrl", profile.baseUrl)
                    put("model", profile.model)
                    profile.temperature?.let { put("temperature", it) }
                    profile.maxTokens?.let { put("maxTokens", it) }
                    profile.contextWindow?.let { put("contextWindow", it) }
                    profile.reasoningEffort?.let { put("reasoningEffort", it) }
                }
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    /**
     * Ключ шифрования. Отдельный алиас от серверного credential: смена одного
     * (например, logout стирает device-token) не должна делать нечитаемыми ключи провайдера.
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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mars.local.provider.key"
        const val PREFS = "mars-local-provider"
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE = "active_id"
        const val KEY_KEY_PREFIX = "api_key."
        const val KEY_IV_PREFIX = "api_key_iv."
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
