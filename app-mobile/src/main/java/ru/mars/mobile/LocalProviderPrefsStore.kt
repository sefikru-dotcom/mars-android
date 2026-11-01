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
 * Схема та же, что у серверного device-credential Марса
 * ([ru.mars.network.credential.AndroidKeyStoreCredentialStore]): AES/GCM, ключ шифрования
 * в AndroidKeyStore, без права чтения из бэкапа (allowBackup=false уже стоит в манифесте).
 * Тот класс здесь не переиспользуется намеренно: у него ОДИН слот и фиксированный алиас
 * под device-token, и запись ключа провайдера туда затёрла бы токен устройства. Схема
 * повторена, алиас — свой (`mars.local.provider.key`), слоты — по профилю.
 *
 * Хранилище намеренно синхронное и дешёвое: профилей единицы, вызов идёт с экрана,
 * а не из горячего цикла ответа.
 *
 * Про миграцию. Слот `api_key.<id>` существовал и как открытый ключ (без IV). Такие
 * слоты при первом обращении шифруются и стираются ([LocalProviderKeyMigration]);
 * решение принимает чистая функция, исполняет [migrateLegacyKeys]. Порядок важен:
 * сначала шифрование, потом стирание — при недоступном Keystore ключ остаётся на месте,
 * и человек не вводит его заново. Ни ключ, ни его шифротекст в логи не попадают:
 * в этом классе нет ни одного `Log`-вызова, а исключения наружу не пробрасываются.
 */
class LocalProviderPrefsStore(private val context: Context) : LocalProviderStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val keyStore: KeyStore? by lazy {
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }.getOrNull()
    }

    /** Миграция старых ключей выполнена (или выполнять нечего). */
    @Volatile
    private var keysMigrated = false

    private val migrationLock = Any()

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
        ensureKeysMigrated()
        val encoded = prefs.getString(KEY_KEY_PREFIX + profileId, null) ?: return null
        val encodedIv = prefs.getString(KEY_IV_PREFIX + profileId, null)
        if (encodedIv == null) {
            // Шифрование при миграции не удалось (Keystore недоступен): отдаём старый
            // открытый ключ, чтобы человек не вводил его заново. Стирание повторится
            // при следующем успешном обращении, а не «когда-нибудь потом».
            return encoded.takeIf { it.isNotBlank() }
        }
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
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            // Пустая строка — это «забыть ключ», а не сохранить пустоту: иначе чат
            // отправил бы запрос с пустым заголовком авторизации и получил 401.
            clearApiKey(profileId)
            return
        }
        ensureKeysMigrated()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ciphertext = cipher.doFinal(trimmed.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString(KEY_KEY_PREFIX + profileId, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV_PREFIX + profileId, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override fun clearApiKey(profileId: String) {
        // IV стирается вместе с ключом: осиротевший IV — мусор, который миграция
        // иначе вычищала бы отдельным проходом.
        prefs.edit().remove(KEY_KEY_PREFIX + profileId).remove(KEY_IV_PREFIX + profileId).apply()
    }

    /**
     * Один проход миграции старых ключей; повторные вызовы ничего не делают.
     *
     * План строит чистая [LocalProviderKeyMigration.plan]; здесь только исполнение.
     * Если зашифровать не удалось, НИЧЕГО не стираем: потерять ключ хуже, чем оставить
     * его открытым ещё на один запуск — человек не должен вводить его заново.
     */
    private fun ensureKeysMigrated() {
        if (keysMigrated) return
        synchronized(migrationLock) {
            if (keysMigrated) return
            keysMigrated = runCatching { migrateLegacyKeys() }.getOrDefault(false)
        }
    }

    /** @return true — prefs приведены к целевому виду (или уже были). */
    private fun migrateLegacyKeys(): Boolean {
        val plan = LocalProviderKeyMigration.plan(prefs.all)
        if (plan.isEmpty) return true

        // Шифруем ВСЁ до единого коммита: частично перенесённый набор ключей хуже,
        // чем неперенесённый.
        val sealedSlots = ArrayList<Pair<String, String>>(plan.toSeal.size * 2)
        for (secret in plan.toSeal) {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val ciphertext = cipher.doFinal(secret.plaintext.toByteArray(StandardCharsets.UTF_8))
            sealedSlots += (KEY_KEY_PREFIX + secret.profileId) to
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            sealedSlots += (KEY_IV_PREFIX + secret.profileId) to
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        }

        // commit(), а не apply(): миграция должна быть на диске до первого чтения ключа,
        // иначе следующий запуск снова увидит открытый слот.
        val editor = prefs.edit()
        plan.toErase.forEach { slot -> editor.remove(slot) }
        sealedSlots.forEach { (slot, value) -> editor.putString(slot, value) }
        return editor.commit()
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

        /** Слоты ключей берутся из плана миграции: разойтись с ним они не могут. */
        const val KEY_KEY_PREFIX = LocalProviderKeyMigration.SLOT_PREFIX
        const val KEY_IV_PREFIX = LocalProviderKeyMigration.IV_PREFIX
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
