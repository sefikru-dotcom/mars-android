package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты чистой части миграции ключа провайдера ([LocalProviderKeyMigration]).
 *
 * Что здесь НЕ проверяется и почему: сам AndroidKeyStore, шифрование AES/GCM и запись
 * в `SharedPreferences` требуют устройства или Robolectric (в модуле его нет). Поэтому
 * проверяется решение «что зашифровать, что стереть, что не трогать» — единственное
 * место, где ошибка стоит человеку потерянного ключа. Исполнение плана в
 * `LocalProviderPrefsStore` проверяется чтением кода, а не тестом.
 */
class LocalProviderKeyMigrationTest {

    private val key = "sk-test-legacy-0123456789"

    /** Снимок prefs с открытым ключом (как его писал старый код: без IV). */
    private fun legacySnapshot(): MutableMap<String, Any?> = mutableMapOf(
        "api_key.plusvibe" to key,
        "profiles" to """[{"id":"plusvibe","baseUrl":"https://plusvibeapi.ru/v1","model":"m"}]""",
        "active_id" to "plusvibe",
    )

    /** Имитация исполнения плана: стирание, затем запись шифротекста в те же слоты. */
    private fun apply(plan: LocalProviderKeyMigration.Plan, snapshot: Map<String, Any?>): MutableMap<String, Any?> {
        val result = snapshot.toMutableMap()
        plan.toErase.forEach { slot -> result.remove(slot) }
        plan.toSeal.forEach { secret ->
            result[LocalProviderKeyMigration.SLOT_PREFIX + secret.profileId] = "ciphertext:${secret.plaintext.length}"
            result[LocalProviderKeyMigration.IV_PREFIX + secret.profileId] = "iv"
        }
        return result
    }

    // --- открытый ключ ---------------------------------------------------------------------

    @Test
    fun `legacy plaintext key is scheduled for sealing and erasing`() {
        val plan = LocalProviderKeyMigration.plan(legacySnapshot())

        assertEquals(
            listOf(LocalProviderKeyMigration.Secret(profileId = "plusvibe", plaintext = key)),
            plan.toSeal,
        )
        assertTrue(plan.toErase.contains("api_key.plusvibe"))
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `after migration no plaintext value survives in prefs`() {
        val snapshot = legacySnapshot()
        val migrated = apply(LocalProviderKeyMigration.plan(snapshot), snapshot)

        // Главное требование: ни одного слота с открытым ключом.
        assertFalse(migrated.values.any { it == key })
        assertTrue(migrated.containsKey("api_key.plusvibe"))
        assertTrue(migrated.containsKey("api_key_iv.plusvibe"))
        // Открытые параметры профиля пережили миграцию байт в байт.
        assertEquals(snapshot["profiles"], migrated["profiles"])
        assertEquals(snapshot["active_id"], migrated["active_id"])
    }

    @Test
    fun `migration is idempotent`() {
        val snapshot = legacySnapshot()
        val once = apply(LocalProviderKeyMigration.plan(snapshot), snapshot)
        val second = LocalProviderKeyMigration.plan(once)

        assertTrue("повторный проход не должен ничего делать", second.isEmpty)
        assertTrue(second.toSeal.isEmpty())
        assertTrue(second.toErase.isEmpty())
    }

    @Test
    fun `secret toString never exposes the key`() {
        val secret = LocalProviderKeyMigration.Secret(profileId = "plusvibe", plaintext = key)
        assertFalse(secret.toString().contains(key))
        assertTrue(secret.toString().contains("plusvibe"))
    }

    // --- уже зашифрованный ключ ------------------------------------------------------------

    @Test
    fun `encrypted pair is kept untouched`() {
        val snapshot = mapOf<String, Any?>(
            "api_key.plusvibe" to "Y2lwaGVydGV4dA==",
            "api_key_iv.plusvibe" to "aXY=",
        )
        val plan = LocalProviderKeyMigration.plan(snapshot)

        assertTrue(plan.isEmpty)
        // Пара «шифротекст + IV» — рабочее состояние: оба слота в toKeep, ни один не трогаем.
        // Сортировка по имени: '.' (46) раньше '_' (95), поэтому ключ идёт перед IV.
        assertEquals(
            listOf("api_key.plusvibe", "api_key_iv.plusvibe"),
            plan.toKeep,
        )
    }

    @Test
    fun `one encrypted and one legacy profile are handled together`() {
        val snapshot = mapOf<String, Any?>(
            "api_key.old" to key,
            "api_key.new" to "Y2lwaGVydGV4dA==",
            "api_key_iv.new" to "aXY=",
        )
        val plan = LocalProviderKeyMigration.plan(snapshot)

        assertEquals(listOf(LocalProviderKeyMigration.Secret("old", key)), plan.toSeal)
        assertEquals(listOf("api_key.old"), plan.toErase)
        assertTrue(plan.toKeep.contains("api_key.new"))
        assertTrue(plan.toKeep.contains("api_key_iv.new"))
    }

    // --- мусор от неудачных записей --------------------------------------------------------

    @Test
    fun `orphan iv is erased`() {
        val plan = LocalProviderKeyMigration.plan(mapOf<String, Any?>("api_key_iv.ghost" to "aXY="))

        assertTrue(plan.toSeal.isEmpty())
        assertEquals(listOf("api_key_iv.ghost"), plan.toErase)
    }

    @Test
    fun `blank value erases slot and its iv`() {
        val plan = LocalProviderKeyMigration.plan(
            mapOf<String, Any?>(
                "api_key.plusvibe" to "   ",
                "api_key_iv.plusvibe" to "aXY=",
            ),
        )

        assertTrue(plan.toSeal.isEmpty())
        assertTrue(plan.toErase.contains("api_key.plusvibe"))
        assertTrue(plan.toErase.contains("api_key_iv.plusvibe"))
    }

    @Test
    fun `non-string value at key slot is treated as junk`() {
        // Слот писали не строкой (битая запись): ключа там нет, и «шифровать» нечего.
        val plan = LocalProviderKeyMigration.plan(mapOf<String, Any?>("api_key.plusvibe" to true))

        assertTrue(plan.toSeal.isEmpty())
        assertEquals(listOf("api_key.plusvibe"), plan.toErase)
    }

    @Test
    fun `blank profile id is erased`() {
        val plan = LocalProviderKeyMigration.plan(mapOf<String, Any?>("api_key." to key))

        assertTrue(plan.toSeal.isEmpty())
        assertEquals(listOf("api_key."), plan.toErase)
    }

    // --- чужие слоты ----------------------------------------------------------------------

    @Test
    fun `profile parameters are never touched`() {
        val snapshot = mapOf<String, Any?>(
            "profiles" to "[]",
            "active_id" to "plusvibe",
            "api_type" to "openai",
            "base_url" to "https://plusvibeapi.ru/v1",
            "model" to "gpt-6.1-sol",
        )
        val plan = LocalProviderKeyMigration.plan(snapshot)

        assertTrue(plan.isEmpty)
        assertTrue(plan.toKeep.isEmpty())
        assertEquals(emptyList<String>(), plan.toErase)
    }

    @Test
    fun `empty prefs produce empty plan`() {
        assertTrue(LocalProviderKeyMigration.plan(emptyMap<String, Any?>()).isEmpty)
    }
}
