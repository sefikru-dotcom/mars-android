package ru.mars.mobile

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.local.LocalProviderProfile
import ru.mars.network.local.LocalProviderState
import ru.mars.network.local.LocalProviderStore
import ru.mars.network.repository.PaidTtsClient
import ru.mars.network.repository.ServerTtsResult

/**
 * Выбор профиля PlusVibe для платного синтеза.
 *
 * ## Что здесь зафиксировано
 * Дефект был не в синтезе, а в ПРОВОДКЕ: адрес и ключ платного голоса брались у АКТИВНОГО
 * профиля (`state().active`). Пока PlusVibe был единственным провайдером, это совпадало с
 * правдой; как только человек заводил второй адрес (Yeta, прокси) и тот становился
 * активным, платный голос терял ключ PlusVibe — или, что хуже, отправлял запрос на чужой
 * хост. Поэтому проверяется ровно то, что обязано быть верным:
 * - профиль PlusVibe находится по ДОМЕНУ адреса, независимо от активного провайдера;
 * - адрес и ключ всегда принадлежат ОДНОМУ профилю;
 * - при отсутствии профиля/ключа нет ни запроса, ни чужого адреса — только явный отказ.
 */
class PlusVibeTtsAccessTest {

    // --- домен ------------------------------------------------------------------------------

    @Test
    fun `plusvibe host is recognised by the registrable domain`() {
        assertTrue(PlusVibeTtsProfile.isPlusVibe("https://plusvibeapi.ru/v1"))
        assertTrue(PlusVibeTtsProfile.isPlusVibe("plusvibeapi.ru"))
        assertTrue(PlusVibeTtsProfile.isPlusVibe("https://api.plusvibe.ai/v1"))
        assertTrue(PlusVibeTtsProfile.isPlusVibe("https://www.plusvibeapi.ru"))
        assertTrue(PlusVibeTtsProfile.isPlusVibe("  https://plusvibeapi.ru/v1/  "))
    }

    @Test
    fun `foreign host is never mistaken for plusvibe`() {
        assertFalse(PlusVibeTtsProfile.isPlusVibe("https://yeta.example/v1"))
        // Подстрока в ПУТИ — не провайдер: ключ туда не поедет.
        assertFalse(PlusVibeTtsProfile.isPlusVibe("https://yeta.example/plusvibe"))
        assertFalse(PlusVibeTtsProfile.isPlusVibe("https://yeta.example/v1?provider=plusvibe"))
        // Поддомен ЧУЖОГО домена тоже чужой: регистрируемая часть — `yeta.example`.
        assertFalse(PlusVibeTtsProfile.isPlusVibe("https://plusvibe.yeta.example/v1"))
        assertFalse(PlusVibeTtsProfile.isPlusVibe("https://plusvibeapi.ru.evil.example/v1"))
        assertFalse(PlusVibeTtsProfile.isPlusVibe(""))
    }

    @Test
    fun `host is parsed without scheme port login and path`() {
        assertEquals("plusvibeapi.ru", PlusVibeTtsProfile.hostOf("https://plusvibeapi.ru/v1"))
        assertEquals("plusvibeapi.ru", PlusVibeTtsProfile.hostOf("plusvibeapi.ru/v1"))
        assertEquals("plusvibeapi.ru", PlusVibeTtsProfile.hostOf("https://plusvibeapi.ru:8443/v1"))
        assertEquals("plusvibeapi.ru", PlusVibeTtsProfile.hostOf("https://user:pass@plusvibeapi.ru/v1"))
        assertNull(PlusVibeTtsProfile.hostOf("   "))
    }

    // --- выбор профиля ----------------------------------------------------------------------

    @Test
    fun `profile selection ignores the active provider`() {
        val state = LocalProviderState(
            profiles = listOf(
                profile("yeta", "https://yeta.example/v1"),
                profile("pv", "https://plusvibeapi.ru/v1"),
            ),
            // Активен ЧУЖОЙ провайдер — именно этот случай ломал платный голос.
            activeId = "yeta",
        )

        assertEquals("pv", PlusVibeTtsProfile.select(state)?.id)
    }

    @Test
    fun `active plusvibe profile wins over an earlier one`() {
        val state = LocalProviderState(
            profiles = listOf(
                profile("pv-old", "https://plusvibeapi.ru/v1"),
                profile("pv-new", "https://plusvibeapi.ru/v1"),
            ),
            activeId = "pv-new",
        )

        assertEquals("pv-new", PlusVibeTtsProfile.select(state)?.id)
    }

    @Test
    fun `no plusvibe profile means no selection`() {
        val state = LocalProviderState(
            profiles = listOf(profile("yeta", "https://yeta.example/v1")),
            activeId = "yeta",
        )

        assertNull(PlusVibeTtsProfile.select(state))
        assertNull(PlusVibeTtsProfile.select(LocalProviderState()))
    }

    // --- привязка адреса и ключа ------------------------------------------------------------

    @Test
    fun `binding takes the key of the plusvibe profile not of the active one`() {
        val store = FakeStore(
            profiles = listOf(
                profile("yeta", "https://yeta.example/v1"),
                profile("pv", "https://plusvibeapi.ru/v1"),
            ),
            activeId = "yeta",
            keys = mapOf("yeta" to "yeta-key", "pv" to "pv-key"),
        )

        val binding = PlusVibeTtsAccess(store).bind()

        assertEquals("pv", binding?.profileId)
        assertEquals("https://plusvibeapi.ru/v1", binding?.baseUrl)
        assertEquals("pv-key", binding?.apiKey)
        // Ключ чужого профиля не читался вовсе: он не имеет отношения к PlusVibe.
        assertEquals(listOf("pv"), store.keyReads)
    }

    @Test
    fun `binding without a key is a profile without a key not a foreign one`() {
        val store = FakeStore(
            profiles = listOf(
                profile("yeta", "https://yeta.example/v1"),
                profile("pv", "https://plusvibeapi.ru/v1"),
            ),
            activeId = "yeta",
            keys = mapOf("yeta" to "yeta-key"),
        )

        val binding = PlusVibeTtsAccess(store).bind()

        assertEquals("pv", binding?.profileId)
        assertNull("ключ чужого провайдера подставлять нельзя", binding?.apiKey)
    }

    @Test
    fun `binding never prints the key`() {
        val store = FakeStore(
            profiles = listOf(profile("pv", "https://plusvibeapi.ru/v1")),
            activeId = "pv",
            keys = mapOf("pv" to "pv-secret-key"),
        )

        val printed = PlusVibeTtsAccess(store).bind().toString()

        assertFalse("секрет не должен попадать в лог/исключение", printed.contains("pv-secret-key"))
        assertTrue(printed.contains("https://plusvibeapi.ru/v1"))
    }

    // --- клиент: куда и с каким ключом уходит запрос -----------------------------------------

    @Test
    fun `paid client sends the request to the plusvibe host with the plusvibe key`() {
        val store = FakeStore(
            profiles = listOf(
                profile("yeta", "https://yeta.example/v1"),
                profile("pv", "https://plusvibeapi.ru/v1"),
            ),
            activeId = "yeta",
            keys = mapOf("yeta" to "yeta-key", "pv" to "pv-key"),
        )
        val transports = mutableListOf<RecordingTransport>()
        val client = PlusVibeTtsClient(
            PlusVibeTtsAccess(store),
            OkHttpClient(),
            newTransport = { baseUrl, apiKey ->
                RecordingTransport(baseUrl, apiKey).also { transports += it }
            },
        )

        val result = runBlocking { client.synthesize("фраза", "Alice", "elevenlabs-v4-tts") }

        assertTrue(result is ServerTtsResult.Audio)
        val call = transports.single().calls.single()
        assertEquals("фраза", call.text)
        assertEquals("https://plusvibeapi.ru/v1", call.baseUrl)
        assertEquals("pv-key", call.apiKey)
        // Именованный голос едет именем: `voice_id` для него не задан.
        assertNull(call.voiceId)
    }

    @Test
    fun `library voice id reaches the transport instead of being swallowed by the wrapper`() {
        // Русские голоса ElevenLabs (Лариса, Катя) выбираются ТОЛЬКО полем `voice_id`.
        // Обёртка обязана пробросить его, иначе человек выберет русский голос, а услышит
        // именованный дефолт провайдера — и никакого признака подмены не будет.
        val store = FakeStore(
            profiles = listOf(profile("pv", "https://plusvibeapi.ru/v1")),
            activeId = "pv",
            keys = mapOf("pv" to "pv-key"),
        )
        val transports = mutableListOf<RecordingTransport>()
        val client = PlusVibeTtsClient(
            PlusVibeTtsAccess(store),
            OkHttpClient(),
            newTransport = { baseUrl, apiKey ->
                RecordingTransport(baseUrl, apiKey).also { transports += it }
            },
        )

        runBlocking {
            client.synthesize(
                text = "фраза",
                voice = "Larisa",
                model = "elevenlabs-v4-tts",
                voiceId = "AB9XsbSA4eLG12t2myjN",
            )
        }

        assertEquals("AB9XsbSA4eLG12t2myjN", transports.single().calls.single().voiceId)
    }

    @Test
    fun `paid client without a plusvibe profile reports unavailability and makes no request`() {
        val store = FakeStore(
            profiles = listOf(profile("yeta", "https://yeta.example/v1")),
            activeId = "yeta",
            keys = mapOf("yeta" to "yeta-key"),
        )
        var transportsBuilt = 0
        val client = PlusVibeTtsClient(
            PlusVibeTtsAccess(store),
            OkHttpClient(),
            newTransport = { baseUrl, apiKey ->
                transportsBuilt++
                RecordingTransport(baseUrl, apiKey)
            },
        )

        val result = runBlocking { client.synthesize("фраза", "Alice", "elevenlabs-v4-tts") }

        val unavailable = result as ServerTtsResult.Unavailable
        assertEquals(
            "отсутствие профиля PlusVibe обязано называться своим кодом",
            PlusVibeTtsProfile.REASON_NO_PROFILE,
            unavailable.reason,
        )
        assertEquals("на чужой хост не должно уйти ни одного запроса", 0, transportsBuilt)
    }

    @Test
    fun `paid client without a key reports unavailability and makes no request`() {
        val store = FakeStore(
            profiles = listOf(profile("pv", "https://plusvibeapi.ru/v1")),
            activeId = "pv",
            keys = emptyMap(),
        )
        var transportsBuilt = 0
        val client = PlusVibeTtsClient(
            PlusVibeTtsAccess(store),
            OkHttpClient(),
            newTransport = { baseUrl, apiKey ->
                transportsBuilt++
                RecordingTransport(baseUrl, apiKey)
            },
        )

        val result = runBlocking { client.synthesize("фраза", "Alice", "elevenlabs-v4-tts") }

        assertEquals(
            PlusVibeTtsProfile.REASON_NO_KEY,
            (result as ServerTtsResult.Unavailable).reason,
        )
        assertEquals("запрос без ключа не имеет смысла", 0, transportsBuilt)
    }

    // --- вспомогательное --------------------------------------------------------------------

    private fun profile(id: String, baseUrl: String): LocalProviderProfile = LocalProviderProfile(
        id = id,
        title = id,
        baseUrl = baseUrl,
        model = "claude-haiku-5.5",
    )

    /** Хранилище без Android: профили, активный id и ключи по профилям. */
    private class FakeStore(
        private val profiles: List<LocalProviderProfile>,
        private val activeId: String?,
        private val keys: Map<String, String?>,
    ) : LocalProviderStore {

        val keyReads = mutableListOf<String>()

        override fun state(): LocalProviderState =
            LocalProviderState(profiles = profiles, activeId = activeId)

        override fun saveProfile(profile: LocalProviderProfile) = unsupported()

        override fun removeProfile(id: String) = unsupported()

        override fun setActive(id: String?) = unsupported()

        override fun apiKey(profileId: String): String? {
            keyReads += profileId
            return keys[profileId]
        }

        override fun saveApiKey(profileId: String, key: String) = unsupported()

        override fun clearApiKey(profileId: String) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("только чтение")
    }

    /** Транспорт, который запоминает, с каким адресом и ключом его позвали. */
    private class RecordingTransport(
        private val baseUrl: () -> String?,
        private val apiKey: () -> String?,
    ) : PaidTtsClient {

        data class Call(val text: String, val baseUrl: String?, val apiKey: String?, val voiceId: String? = null)

        val calls = mutableListOf<Call>()

        override suspend fun synthesize(
            text: String,
            voice: String,
            model: String,
            speed: Float,
            instructions: String?,
            voiceId: String?,
        ): ServerTtsResult {
            calls += Call(text, baseUrl(), apiKey(), voiceId)
            return ServerTtsResult.Audio(byteArrayOf(1, 2, 3), "audio/mpeg")
        }
    }
}
