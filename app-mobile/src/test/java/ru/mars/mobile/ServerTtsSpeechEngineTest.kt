package ru.mars.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.EdgeNeuralTtsClient
import ru.mars.network.repository.OpenAiSpeechTtsClient
import ru.mars.network.repository.PaidTtsClient
import ru.mars.network.repository.ServerTtsContract
import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsVoiceProfile
import ru.mars.network.repository.ServerTtsVoices

/**
 * Поведение серверного TTS с ПРОЗРАЧНЫМ фолбэком на системный Android TTS.
 *
 * Проверяется чистый JVM-движок [ServerTtsSpeechEngine] и мост
 * [RepositoryServerTtsClient]: сеть, файлы и AudioTrack подменены фейками.
 *
 * Диспетчер тестов — [Dispatchers.Unconfined]: фейковый синтез возвращает результат без
 * реальной приостановки, поэтому тело корутины исполняется синхронно внутри [speak] и
 * утверждения сразу после вызова детерминированы (никакого ожидания и таймеров).
 */
class ServerTtsSpeechEngineTest {

    /** Системный TTS: запоминает реплики и может отвечать отказом. */
    private class FakeSystemTts(private var accept: Boolean = true) : VoiceOutputController.SpeechEngine {
        val spoken = mutableListOf<String>()
        var stopCount = 0
        var shutdownCount = 0
        var failNext = false

        override fun speak(text: String): Boolean {
            if (failNext || !accept) {
                failNext = false
                return false
            }
            spoken += text
            return true
        }

        override fun stop() {
            stopCount++
        }

        override fun shutdown() {
            shutdownCount++
        }
    }

    /** Серверный синтез: возвращает заранее заданный исход (или бросает, если задано). */
    private class FakeClient(
        private var result: ServerTtsResult = ServerTtsResult.Unavailable("unauthorized", 401),
        private val throwOnSynthesize: Boolean = false,
    ) : ServerTtsClient {
        val requests = mutableListOf<String>()

        override suspend fun synthesize(text: String): ServerTtsResult {
            requests += text
            if (throwOnSynthesize) throw IllegalStateException("boom")
            return result
        }

        fun setResult(next: ServerTtsResult) {
            result = next
        }
    }

    /** Воспроизведение: запоминает байты и умеет «не суметь запуститься». */
    private class FakePlayback(private var startable: Boolean = true) : ServerTtsPlayback {
        val played = mutableListOf<ByteArray>()
        var stopCount = 0
        var releaseCount = 0
        private var pendingFailure: (() -> Unit)? = null

        override fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean {
            if (!startable) return false
            played += bytes
            pendingFailure = onFailed
            return true
        }

        /** Имитация асинхронного отказа декодера уже принятой реплики. */
        fun failAsync() {
            pendingFailure?.invoke()
        }

        override fun stop() {
            stopCount++
        }

        override fun release() {
            releaseCount++
        }
    }

    private val audio = ServerTtsResult.Audio(byteArrayOf(1, 2, 3), "audio/mpeg")

    private fun engine(
        system: FakeSystemTts = FakeSystemTts(),
        // Порт, а не конкретный фейк: часть тестов проверяет, что движок доносит выбор голоса
        // до НАСТОЯЩЕГО моста ([RepositoryServerTtsClient]) через метод порта.
        client: ServerTtsClient = FakeClient(),
        playback: FakePlayback = FakePlayback(),
        onServerAudioAvailable: () -> Unit = {},
        fallbackChannel: VoiceChannel = VoiceChannel.SYSTEM,
        onVoiceSubstituted: (VoiceSubstitution) -> Unit = {},
        onSilent: () -> Unit = {},
    ) = ServerTtsSpeechEngine(
        fallback = system,
        client = client,
        playback = playback,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onServerAudioAvailable = onServerAudioAvailable,
        fallbackChannel = fallbackChannel,
        onVoiceSubstituted = onVoiceSubstituted,
        onSilent = onSilent,
    )

    // --- Серверный путь --------------------------------------------------------------------

    @Test
    fun `server audio is played and system tts stays silent`() {
        val system = FakeSystemTts()
        val client = FakeClient(audio)
        val playback = FakePlayback()
        val engine = engine(system, client, playback)

        assertTrue(engine.speak("привет, Марс"))

        assertEquals(listOf("привет, Марс"), client.requests)
        assertEquals(1, playback.played.size)
        assertTrue(system.spoken.isEmpty())
    }

    @Test
    fun `server audio announces availability once`() {
        var announcements = 0
        val engine = engine(client = FakeClient(audio), onServerAudioAvailable = { announcements++ })

        engine.speak("раз")
        engine.speak("два")

        assertEquals(1, announcements)
    }

    // --- Фолбэк на системный TTS ----------------------------------------------------------

    @Test
    fun `unauthorized response falls back to system tts with the same text`() {
        val system = FakeSystemTts()
        val engine = engine(system, FakeClient(ServerTtsResult.Unavailable("unauthorized", 401)))

        assertTrue(engine.speak("сервер закрыт"))

        assertEquals(listOf("сервер закрыт"), system.spoken)
    }

    @Test
    fun `not found and server errors fall back to system tts`() {
        for (reason in listOf("not_found", "rate_limited", "server_error", "invalid_response", "transport")) {
            val system = FakeSystemTts()
            val engine = engine(system, FakeClient(ServerTtsResult.Unavailable(reason)))

            assertTrue("фолбэк не сработал для $reason", engine.speak("текст"))

            assertEquals("нет фолбэка для $reason", listOf("текст"), system.spoken)
        }
    }

    @Test
    fun `playback that cannot start falls back to system tts`() {
        val system = FakeSystemTts()
        val engine = engine(system, FakeClient(audio), FakePlayback(startable = false))

        assertTrue(engine.speak("не заиграло"))

        assertEquals(listOf("не заиграло"), system.spoken)
    }

    @Test
    fun `async playback failure falls back to system tts`() {
        val system = FakeSystemTts()
        val playback = FakePlayback()
        val engine = engine(system, FakeClient(audio), playback)

        engine.speak("битый поток")
        assertTrue(system.spoken.isEmpty())
        playback.failAsync()

        assertEquals(listOf("битый поток"), system.spoken)
    }

    @Test
    fun `client exception degrades to system tts instead of crashing`() {
        val system = FakeSystemTts()
        val engine = engine(system, FakeClient(throwOnSynthesize = true))

        assertTrue(engine.speak("исключение"))

        assertEquals(listOf("исключение"), system.spoken)
    }

    // --- Правило длины (5000) -------------------------------------------------------------

    @Test
    fun `text longer than 5000 characters never reaches the server`() {
        val system = FakeSystemTts()
        val client = FakeClient(audio)
        val engine = engine(system, client)
        val tooLong = "я".repeat(ServerTtsContract.MAX_TEXT_LENGTH + 1)

        assertTrue(engine.speak(tooLong))

        assertEquals(0, engine.serverAttemptCount)
        assertTrue(client.requests.isEmpty())
        assertEquals(listOf(tooLong), system.spoken)
    }

    @Test
    fun `text of exactly 5000 characters is sent to the server`() {
        val client = FakeClient(audio)
        val engine = engine(client = client)
        val limit = "я".repeat(ServerTtsContract.MAX_TEXT_LENGTH)

        assertTrue(engine.speak(limit))

        assertEquals(1, engine.serverAttemptCount)
        assertEquals(listOf(limit), client.requests)
    }

    // --- Тишина и жизненный цикл ----------------------------------------------------------

    @Test
    fun `stop silences both the server playback and the system engine`() {
        val system = FakeSystemTts()
        val playback = FakePlayback()
        val engine = engine(system, FakeClient(audio), playback)

        engine.stop()

        assertEquals(1, playback.stopCount)
        assertEquals(1, system.stopCount)
    }

    @Test
    fun `shutdown releases the playback and the system engine`() {
        val system = FakeSystemTts()
        val playback = FakePlayback()
        val engine = engine(system, FakeClient(audio), playback)

        engine.shutdown()

        assertEquals(1, playback.releaseCount)
        assertEquals(1, system.shutdownCount)
    }

    @Test
    fun `both channels dead is reported as not accepted`() {
        val system = FakeSystemTts(accept = false)
        val engine = engine(system, FakeClient(ServerTtsResult.Unavailable("unauthorized", 401)))

        assertTrue(engine.speak("первый раз принят в попытку"))
        assertFalse("мёртвый системный TTS и недоступный сервер не должны обещать звук", engine.speak("второй раз"))
    }

    // --- Последний резерв: Piper не готов (модель не скачана) ------------------------------

    @Test
    fun `dead offline reserve falls through to the platform and announces it`() {
        // Piper ещё не поднят (21 МиБ модели не скачаны) — это не «звука нет вовсе».
        val piper = FakeSystemTts(accept = false)
        val platform = FakeSystemTts()
        val events = mutableListOf<VoiceSubstitution>()
        val engine = ServerTtsSpeechEngine(
            fallback = piper,
            client = FakeClient(ServerTtsResult.Unavailable("transport")),
            playback = FakePlayback(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            fallbackChannel = VoiceChannel.OFFLINE,
            lastResort = platform,
            onVoiceSubstituted = { events += it },
        )

        assertTrue("фраза обязана прозвучать хотя бы платформой", engine.speak("фраза"))

        assertEquals(listOf("фраза"), platform.spoken)
        // Объявляется ФАКТИЧЕСКИЙ канал: звучит платформа, а не «локальный голос».
        assertEquals(
            listOf(VoiceChannel.SYSTEM to VoiceSubstitutionReason.SYSTEM_RESERVE),
            events.map { it.spoken to it.reason },
        )
    }

    @Test
    fun `dead reserve and dead platform still report silence instead of pretending`() {
        val piper = FakeSystemTts(accept = false)
        val platform = FakeSystemTts(accept = false)
        var silent = 0
        val engine = ServerTtsSpeechEngine(
            fallback = piper,
            client = FakeClient(ServerTtsResult.Unavailable("transport")),
            playback = FakePlayback(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            fallbackChannel = VoiceChannel.OFFLINE,
            lastResort = platform,
            onSilent = { silent++ },
        )

        assertTrue("реплика принята в попытку", engine.speak("фраза"))
        assertEquals(1, silent)
        assertTrue(platform.spoken.isEmpty())
    }

    // --- Мост «сеть + credential» ---------------------------------------------------------

    @Test
    fun `missing credential is a fallback request, not an unauthenticated call`() {
        var transportCalls = 0
        val client = RepositoryServerTtsClient(
            baseUrl = "https://marscore.ru",
            credentialProvider = { null },
            // Прямой сокет отключён: тест про серверный маршрут, а не про сеть.
            directEdgeClient = null,
            transport = ServerTtsTransport { _, _, _ ->
                transportCalls++
                ServerTtsResult.Audio(byteArrayOf(9), "audio/mpeg")
            },
        )

        val result = kotlinx.coroutines.runBlocking { client.synthesize("текст") }

        assertEquals(0, transportCalls)
        assertTrue(result is ServerTtsResult.Unavailable)
        assertEquals("unauthorized", (result as ServerTtsResult.Unavailable).reason)
    }

    @Test
    fun `bridge forwards text and the explicit voice profile to the repository transport`() {
        val credential = ConsumedDeviceCredential(
            accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
            scopes = listOf("chat:send"), expiresIn = 3600,
        )
        var seenUrl: String? = null
        var seenText: String? = null
        var seenRate: String? = null
        var seenEngine: String? = null
        var seenVoice: String? = null
        val client = RepositoryServerTtsClient(
            baseUrl = "https://marscore.ru",
            credentialProvider = { credential },
            directEdgeClient = null,
            transport = ServerTtsTransport { url, _, request ->
                seenUrl = url
                seenText = request.text
                seenRate = request.rate
                seenEngine = request.engine
                seenVoice = request.voice
                ServerTtsResult.Audio(byteArrayOf(7), "audio/mpeg")
            },
        )

        val result = kotlinx.coroutines.runBlocking { client.synthesize("динамика") }

        assertTrue(result is ServerTtsResult.Audio)
        assertEquals("https://marscore.ru", seenUrl)
        assertEquals("динамика", seenText)
        // Подтверждённый контракт: естественный голос запрашивается явно, а не «по умолчанию».
        assertEquals("edge", seenEngine)
        assertEquals("ru-RU-SvetlanaNeural", seenVoice)
        assertEquals("+8%", seenRate)
        assertEquals("natural-svetlana", client.lastProfileId)
    }

    @Test
    fun `engine holds no android types so the fallback policy stays unit testable`() {
        val androidFields = ServerTtsSpeechEngine::class.java.declaredFields
            .map { it.type.name }
            .filter { it.startsWith("android.") }
        assertTrue("Android-зависимость в движке озвучки: $androidFields", androidFields.isEmpty())
    }

    // --- Цепочка профилей: откат по ФАКТИЧЕСКОМУ ответу сервера --------------------------
    //
    // Требование: естественный голос остаётся первым, но его отказ (503 «движок не
    // установлен», 400 «невалидный голос», таймаут) не съедает фразу — тот же текст уходит
    // следующим профилем [ServerTtsVoices.VOICE_DIALOG], а не сразу в системный TTS.
    // Проверяется наблюдаемое: какой engine реально ушёл в запрос и что вернулось наружу.

    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    /** Мост с записывающим транспортом: единственное место, где видно ушедший профиль. */
    private fun bridge(
        selector: SpeechVoiceProfileSelector = SpeechVoiceProfileSelector(),
        sleeper: suspend (Long) -> Unit = {},
        transport: ServerTtsTransport,
    ) = RepositoryServerTtsClient(
        baseUrl = "https://marscore.ru",
        credentialProvider = { credential },
        selector = selector,
        // Прямой сокет выключен: эти тесты проверяют СЕРВЕРНУЮ цепочку профилей, а не сеть.
        // Без этого каждый тест открывал бы настоящий WebSocket к Microsoft.
        directEdgeClient = null,
        transport = transport,
        // Пауза лимитера в тестах не ждётся: проверяется решение «подождать и повторить»,
        // а не длительность ожидания.
        sleeper = sleeper,
    )

    @Test
    fun `edge engine unavailable falls back to the local profile inside the same utterance`() {
        val engines = mutableListOf<String?>()
        val client = bridge { _, _, request ->
            engines += request.engine
            if (request.engine == "edge") {
                ServerTtsResult.Unavailable("server_error", 503)
            } else {
                ServerTtsResult.Audio(byteArrayOf(5), "audio/wav")
            }
        }

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue("503 по естественному голосу не должен съедать фразу", result is ServerTtsResult.Audio)
        // Оба сетевых голоса (Светлана, Дмитрий) живут на одном движке edge: если он не
        // установлен, отказ получают оба, и фразу подхватывает локальный piper.
        assertEquals(listOf("edge", "edge", "edge", "piper"), engines)
        assertEquals("local-irina", client.lastProfileId)
    }

    @Test
    fun `invalid svetlana on the server falls back to the other web voice`() {
        val voices = mutableListOf<String?>()
        val client = bridge { _, _, request ->
            voices += request.voice
            if (request.voice == "ru-RU-SvetlanaNeural") {
                ServerTtsResult.Unavailable("http_400", 400)
            } else {
                ServerTtsResult.Audio(byteArrayOf(6), "audio/wav")
            }
        }

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue("400 invalid voice не должен оставлять фразу без звука", result is ServerTtsResult.Audio)
        assertEquals(listOf("ru-RU-SvetlanaNeural", "ru-RU-DmitryNeural"), voices)
        assertEquals("natural-dmitry", client.lastProfileId)
    }

    @Test
    fun `transport timeout on the natural voice falls back to the local profile`() {
        val engines = mutableListOf<String?>()
        val client = bridge { _, _, request ->
            engines += request.engine
            if (request.engine == "edge") {
                ServerTtsResult.Unavailable("transport")
            } else {
                ServerTtsResult.Audio(byteArrayOf(7), "audio/wav")
            }
        }

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue("таймаут не должен съедать фразу", result is ServerTtsResult.Audio)
        assertEquals(listOf("edge", "edge", "edge", "piper"), engines)
    }

    @Test
    fun `exhausted chain is an explicit failure instead of a silent one`() {
        val engines = mutableListOf<String?>()
        val client = bridge { _, _, request ->
            engines += request.engine
            if (request.engine == "edge") {
                ServerTtsResult.Unavailable("server_error", 503)
            } else {
                ServerTtsResult.Unavailable("http_400", 400)
            }
        }

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertEquals("цепочка должна быть пройдена целиком", listOf("edge", "edge", "edge", "piper"), engines)
        assertTrue("исчерпанная цепочка — это ошибка, а не пустой ответ", result is ServerTtsResult.Unavailable)
        assertEquals("profiles_exhausted", (result as ServerTtsResult.Unavailable).reason)
    }

    @Test
    fun `rate limit is retried once and never switches the voice`() {
        val engines = mutableListOf<String?>()
        val client = bridge { _, _, request ->
            engines += request.engine
            ServerTtsResult.Unavailable("rate_limited", 429)
        }

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        // Один повтор после паузы лимитера — и всё: третий запрос только добил бы лимитер,
        // а другой голос не помог бы (лимитер общий на клиента).
        assertEquals("edge", engines.distinct().single())
        assertEquals(2, engines.size)
        assertEquals("rate_limited", (result as ServerTtsResult.Unavailable).reason)
    }

    @Test
    fun `a second attempt after the limiter window returns the audio`() {
        val attempts = mutableListOf<Int>()
        val client = bridge { _, _, _ ->
            attempts += attempts.size
            if (attempts.size == 1) {
                // Первый ответ — лимитер с просьбой подождать 700 мс.
                ServerTtsResult.Unavailable("rate_limited", 429, retryAfterMs = 700L)
            } else {
                ServerTtsResult.Audio(byteArrayOf(4, 2), "audio/mpeg")
            }
        }

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue("второй запрос после паузы обязан дать звук", result is ServerTtsResult.Audio)
        assertEquals(2, attempts.size)
        assertEquals("natural-svetlana", client.lastProfileId)
    }

    @Test
    fun `cooling chain never sends a request without a voice profile`() {
        val engines = mutableListOf<String?>()
        val client = bridge { _, _, request ->
            engines += request.engine
            if (request.engine == "edge") {
                ServerTtsResult.Unavailable("server_error", 503)
            } else {
                ServerTtsResult.Unavailable("http_400", 400)
            }
        }

        kotlinx.coroutines.runBlocking { client.synthesize("первая") }
        val afterFirst = engines.size
        val second = kotlinx.coroutines.runBlocking { client.synthesize("вторая") }

        assertEquals("все профили на паузе — запрос без engine/voice не отправляется", afterFirst, engines.size)
        assertTrue("профиль-less запрос вернул бы чужой голос сервера", second is ServerTtsResult.Unavailable)
    }

    @Test
    fun `after a demotion the next utterance starts local and the natural voice returns after the pause`() {
        var now = 0L
        var edgeHealthy = false
        val engines = mutableListOf<String?>()
        val selector = SpeechVoiceProfileSelector(nowMs = { now })
        val client = bridge(selector) { _, _, request ->
            engines += request.engine
            if (request.engine == "edge" && !edgeHealthy) {
                ServerTtsResult.Unavailable("server_error", 503)
            } else {
                ServerTtsResult.Audio(byteArrayOf(8), if (request.engine == "edge") "audio/mpeg" else "audio/wav")
            }
        }

        kotlinx.coroutines.runBlocking { client.synthesize("раз") }
        assertEquals(listOf("edge", "edge", "edge", "piper"), engines)

        // Тот же экран, следующий ответ: естественный голос на паузе — сразу локальный, без 503.
        engines.clear()
        kotlinx.coroutines.runBlocking { client.synthesize("два") }
        assertEquals(listOf("piper"), engines)

        // Сервер ожил: после паузы снова пробуем ЖЕЛАННЫЙ голос, а не остаёмся на резерве.
        edgeHealthy = true
        now += SpeechVoiceProfileSelector.DEFAULT_TRANSIENT_COOLDOWN_MS + 1
        engines.clear()
        kotlinx.coroutines.runBlocking { client.synthesize("три") }
        assertEquals(listOf("edge"), engines)
        assertEquals("natural-svetlana", client.lastProfileId)
    }

    @Test
    fun `exhausted chain reports which profile failed and why`() {
        val client = bridge { _, _, request ->
            if (request.engine == "edge") {
                ServerTtsResult.Unavailable("server_error", 503)
            } else {
                ServerTtsResult.Unavailable("http_400", 400)
            }
        }

        kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        // Причина каждого шага видна снаружи: «голос сменился» без причины необъяснимо.
        // Первым в списке идёт ОСНОВНОЙ голос (PlusVibe Alice): платного клиента в этом мосте
        // нет, поэтому его отказ — `paid_client_absent` без HTTP-кода. Дальше обычная цепочка
        // бесплатных профилей.
        assertEquals(
            listOf(
                "plusvibe-alice" to null,
                "natural-svetlana" to 503,
                "natural-dmitry" to 503,
                "natural-ava" to 503,
                "local-irina" to 400,
            ),
            client.lastAttempts.map { it.profileId to it.httpCode },
        )
        assertEquals(
            "paid_client_absent",
            client.lastAttempts.first().reason,
        )
    }

    // --- Выбранный человеком голос идёт ПЕРВЫМ --------------------------------------------
    //
    // Требование: выбор в шторке («Светлана» / «Дмитрий» / «Ирина») обязан слышаться.
    // Раньше `synthesize` брал `selector.candidates()` и игнорировал `voiceId`, поэтому
    // любой выбор звучал дефолтным голосом. Проверяется наблюдаемое: какой `voice` ушёл
    // в запрос ПЕРВЫМ.

    /** Мост с явно заданным выбором человека (без живого провайдера). */
    private fun bridgeWithProsody(
        prosody: VoiceProsody,
        selector: SpeechVoiceProfileSelector = SpeechVoiceProfileSelector(),
        prosodyProvider: (() -> VoiceProsody)? = null,
        transport: ServerTtsTransport,
    ) = RepositoryServerTtsClient(
        baseUrl = "https://marscore.ru",
        credentialProvider = { credential },
        selector = selector,
        prosodyProvider = prosodyProvider,
        prosody = prosody,
        directEdgeClient = null,
        transport = transport,
    )

    @Test
    fun `chosen dmitry is requested first instead of the default svetlana`() {
        val voices = mutableListOf<String?>()
        val client = bridgeWithProsody(
            VoiceProsody(voiceId = ServerTtsVoices.NATURAL_DMITRY.id),
        ) { _, _, request ->
            voices += request.voice
            ServerTtsResult.Audio(byteArrayOf(1), "audio/mpeg")
        }

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue(result is ServerTtsResult.Audio)
        assertEquals("выбор «Дмитрий» обязан звучать Дмитрием", listOf("ru-RU-DmitryNeural"), voices)
        assertEquals("natural-dmitry", client.lastProfileId)
    }

    @Test
    fun `chosen irina is requested first even though the web voice is available`() {
        val engines = mutableListOf<String?>()
        val client = bridgeWithProsody(
            VoiceProsody(voiceId = ServerTtsVoices.LOCAL_IRINA.id),
        ) { _, _, request ->
            engines += request.engine
            ServerTtsResult.Audio(byteArrayOf(2), "audio/wav")
        }

        kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertEquals("выбор «Ирина» не должен перебиваться Светланой", listOf("piper"), engines)
        assertEquals("local-irina", client.lastProfileId)
    }

    @Test
    fun `the live prosody provider wins over the stored prosody`() {
        val voices = mutableListOf<String?>()
        val client = bridgeWithProsody(
            prosody = VoiceProsody(voiceId = ServerTtsVoices.NATURAL_DMITRY.id),
            prosodyProvider = { VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id) },
        ) { _, _, request ->
            voices += request.voice
            ServerTtsResult.Audio(byteArrayOf(3), "audio/mpeg")
        }

        kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        // Шторка меняет выбор посреди диалога: снимок из конструктора уже неактуален.
        assertEquals(listOf("ru-RU-SvetlanaNeural"), voices)
    }

    @Test
    fun `unknown voice id keeps the default order instead of a broken profile`() {
        val voices = mutableListOf<String?>()
        val client = bridgeWithProsody(VoiceProsody(voiceId = "удалённый-голос")) { _, _, request ->
            voices += request.voice
            ServerTtsResult.Audio(byteArrayOf(4), "audio/mpeg")
        }

        kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertEquals(listOf("ru-RU-SvetlanaNeural"), voices)
        assertEquals("natural-svetlana", client.lastProfileId)
    }

    @Test
    fun `chosen voice has no duplicates in the chain`() {
        val ids = mutableListOf<String?>()
        val client = bridgeWithProsody(
            VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id),
        ) { _, _, request ->
            ids += request.voice
            ServerTtsResult.Unavailable("server_error", 503)
        }

        kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        // Светлана первая и ровно один раз: остальные профили идут хвостом.
        assertEquals(
            listOf(
                "ru-RU-SvetlanaNeural",
                "ru-RU-DmitryNeural",
                "en-US-AvaMultilingualNeural",
                "ru_RU-irina-medium",
            ),
            ids,
        )
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `changing the voice clears the temporary cooldown of the new choice`() {
        val engines = mutableListOf<String?>()
        val selector = SpeechVoiceProfileSelector(nowMs = { 0L })
        val client = bridgeWithProsody(
            prosody = VoiceProsody(),
            selector = selector,
        ) { _, _, request ->
            engines += request.engine
            if (request.engine == "edge") {
                ServerTtsResult.Unavailable("server_error", 503)
            } else {
                ServerTtsResult.Unavailable("http_400", 400)
            }
        }

        // Все профили отказали: оба edge-голоса понижены на 45 с, piper — на 10 минут.
        val first = kotlinx.coroutines.runBlocking { client.synthesize("раз") }
        assertEquals(listOf("edge", "edge", "edge", "piper"), engines)
        assertEquals("profiles_exhausted", (first as ServerTtsResult.Unavailable).reason)

        // Человек выбрал Ирину руками: паузы сняты, и после её отказа сетевые голоса снова
        // получают попытку (без `reset()` они бы ещё висели на кулдауне и не запрашивались).
        client.setProsody(VoiceProsody(voiceId = ServerTtsVoices.LOCAL_IRINA.id))
        engines.clear()
        kotlinx.coroutines.runBlocking { client.synthesize("два") }

        assertEquals(listOf("piper", "edge", "edge", "edge"), engines)
    }

    @Test
    fun `changing only the tempo keeps the cooldown knowledge`() {
        val engines = mutableListOf<String?>()
        val selector = SpeechVoiceProfileSelector(nowMs = { 0L })
        val client = bridgeWithProsody(
            prosody = VoiceProsody(),
            selector = selector,
        ) { _, _, request ->
            engines += request.engine
            if (request.engine == "edge") {
                ServerTtsResult.Unavailable("server_error", 503)
            } else {
                ServerTtsResult.Audio(byteArrayOf(6), "audio/wav")
            }
        }

        kotlinx.coroutines.runBlocking { client.synthesize("раз") }
        engines.clear()

        // Темп — не смена голоса: «голос не работает» остаётся фактом, лишних запросов нет.
        client.setProsody(VoiceProsody(speed = 1.15f))
        kotlinx.coroutines.runBlocking { client.synthesize("два") }

        assertEquals(listOf("piper"), engines)
    }

    // --- PreferOfflineEngine: выбранный голос решает, кто говорит --------------------------
    //
    // Требование: выбран сетевой голос («как в вебе», Светлана, Дмитрий) — реплику получает
    // серверный движок; выбран local-irina — сразу Piper. Раньше обёртка всегда звала Piper.

    @Test
    fun `network voice goes to the server channel and piper stays silent`() {
        val offline = FakeSystemTts()
        val system = FakeSystemTts()
        val client = FakeClient(audio)
        val playback = FakePlayback()
        val prefer = PreferOfflineEngine(offline, engine(system, client, playback))

        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id))
        assertTrue(prefer.speak("привет, Марс"))

        assertEquals(listOf("привет, Марс"), client.requests)
        assertEquals(1, playback.played.size)
        assertTrue("при выбранном сетевом голосе Piper звучать не должен", offline.spoken.isEmpty())
    }

    @Test
    fun `chosen dmitry really reaches the server as dmitry`() {
        val voices = mutableListOf<String?>()
        val client = bridgeWithProsody(
            VoiceProsody(voiceId = ServerTtsVoices.NATURAL_DMITRY.id),
        ) { _, _, request ->
            voices += request.voice
            ServerTtsResult.Audio(byteArrayOf(7), "audio/mpeg")
        }
        val offline = FakeSystemTts()
        val prefer = PreferOfflineEngine(
            offline = offline,
            fallback = ServerTtsSpeechEngine(
                fallback = FakeSystemTts(),
                client = client,
                playback = FakePlayback(),
                scope = CoroutineScope(Dispatchers.Unconfined),
            ),
        )

        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_DMITRY.id))
        assertTrue(prefer.speak("привет"))

        assertEquals(listOf("ru-RU-DmitryNeural"), voices)
        assertTrue(offline.spoken.isEmpty())
    }

    @Test
    fun `chosen irina speaks locally without touching the server`() {
        val offline = FakeSystemTts()
        val client = FakeClient(audio)
        val prefer = PreferOfflineEngine(offline, engine(client = client))

        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.LOCAL_IRINA.id))
        assertTrue(prefer.speak("привет"))

        assertEquals(listOf("привет"), offline.spoken)
        assertTrue("локальный выбор не должен ходить в сеть", client.requests.isEmpty())
    }

    @Test
    fun `voice id null means the web voice and goes to the server`() {
        val offline = FakeSystemTts()
        val client = FakeClient(audio)
        val prefer = PreferOfflineEngine(offline, engine(client = client))

        prefer.setProsody(VoiceProsody())
        assertTrue(prefer.speak("привет"))

        assertEquals(listOf("привет"), client.requests)
        assertTrue(offline.spoken.isEmpty())
    }

    @Test
    fun `dead server channel hands the phrase to piper instead of silence`() {
        val offline = FakeSystemTts()
        val prefer = PreferOfflineEngine(
            offline = offline,
            // Системный TTS мёртв, сервер отвечает 401: сетевой канал не даст звука.
            fallback = engine(
                system = FakeSystemTts(accept = false),
                client = FakeClient(ServerTtsResult.Unavailable("unauthorized", 401)),
            ),
        )
        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id))

        assertTrue(prefer.speak("первый"))
        assertTrue("первая попытка уходит в серверный канал", offline.spoken.isEmpty())

        // Оба канала мертвы — обёртка честно пробует локальный Piper, а не молчит.
        assertTrue(prefer.speak("второй"))
        assertEquals(listOf("второй"), offline.spoken)
    }

    @Test
    fun `fallback that cannot synthesize from the server keeps piper first`() {
        val offline = FakeSystemTts()
        val system = FakeSystemTts()
        val prefer = PreferOfflineEngine(offline, system)

        prefer.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id))
        assertTrue(prefer.speak("привет"))

        // Запасной движок — платформенный TTS, серверного канала нет: прежний порядок.
        assertEquals(listOf("привет"), offline.spoken)
        assertTrue(system.spoken.isEmpty())
    }

    // --- Прямой нейросетевой сокет идёт ПЕРВЫМ ------------------------------------------
    //
    // Требование Волны 20: звук не должен зависеть от серверного `/api/tts` (его лимитер
    // 2 с и просроченный токен превращали все «нейро»-голоса в один системный робот).
    // Проверяется наблюдаемое: какой голос реально запрошен у сокета и что вернулось.

    /** Подмена прямого сокета: запоминает запрос и отвечает заданным исходом. */
    private class FakeEdge(
        private var result: ServerTtsResult = ServerTtsResult.Audio(byteArrayOf(1), "audio/mpeg"),
    ) : EdgeNeuralTtsClient() {
        val calls = mutableListOf<Triple<String, String?, String?>>()

        override suspend fun synthesize(
            text: String,
            voice: String,
            rate: String?,
            pitch: String?,
        ): ServerTtsResult {
            calls += Triple(voice, rate, pitch)
            return result
        }

        fun setResult(next: ServerTtsResult) {
            result = next
        }
    }

    /** Подмена платного облачного клиента: запоминает запрос и отвечает заданным исходом. */
    private class FakeOpenAi(
        private var result: ServerTtsResult = ServerTtsResult.Audio(byteArrayOf(9), "audio/mpeg"),
    ) : OpenAiSpeechTtsClient() {
        val calls = mutableListOf<Triple<String, String, Float>>()
        /** `voice_id`, с которым позвали: русские голоса едут только так. */
        val voiceIds = mutableListOf<String?>()

        override suspend fun synthesize(
            text: String,
            voice: String,
            model: String,
            speed: Float,
            instructions: String?,
            voiceId: String?,
        ): ServerTtsResult {
            calls += Triple(text, voice, speed)
            voiceIds += voiceId
            return result
        }

        fun setResult(next: ServerTtsResult) {
            result = next
        }
    }

    /** Мост с прямым сокетом и (опционально) серверным транспортом. */
    private fun bridgeWithEdge(
        edge: EdgeNeuralTtsClient?,
        credential: ConsumedDeviceCredential? = this.credential,
        prosody: VoiceProsody? = null,
        transport: ServerTtsTransport = ServerTtsTransport { _, _, _ ->
            ServerTtsResult.Unavailable("server_error", 503)
        },
    ) = RepositoryServerTtsClient(
        baseUrl = "https://marscore.ru",
        credentialProvider = { credential },
        prosody = prosody,
        directEdgeClient = edge,
        transport = transport,
    )

    @Test
    fun `direct socket speaks without any device credential`() {
        val edge = FakeEdge()
        var transportCalls = 0
        val client = bridgeWithEdge(
            edge = edge,
            credential = null,
            transport = ServerTtsTransport { _, _, _ ->
                transportCalls++
                ServerTtsResult.Unavailable("unauthorized", 401)
            },
        )

        val result = kotlinx.coroutines.runBlocking { client.synthesize("привет") }

        assertTrue("без токена звук обязан прийти из сокета", result is ServerTtsResult.Audio)
        assertEquals(0, transportCalls)
        // Голоса не выбирали — значит звучит ОСНОВНОЙ голос (PlusVibe). Платного клиента в
        // этом мосте нет вовсе, поэтому честный резерв — прямой нейро-голос Дмитрия: он
        // единственный доступен без токена Марса. Светлана здесь не звучит намеренно, иначе
        // «основной голос» снова зависел бы от порядка бесплатных профилей.
        assertEquals(listOf(Triple("ru-RU-DmitryNeural", "+5%", "+0Hz")), edge.calls)
        assertEquals("natural-dmitry", client.lastProfileId)
    }

    @Test
    fun `direct socket uses the chosen voice and the person tempo`() {
        val edge = FakeEdge()
        val client = bridgeWithEdge(
            edge = edge,
            prosody = VoiceProsody(voiceId = ServerTtsVoices.NATURAL_AVA.id, speed = 1.15f),
        )

        kotlinx.coroutines.runBlocking { client.synthesize("hello") }

        // Темп человека перебивает темп профиля: 1.15× обязан звучать как +15%, а не +5%.
        assertEquals(listOf(Triple("en-US-AvaMultilingualNeural", "+15%", "+0Hz")), edge.calls)
        assertEquals("natural-ava", client.lastProfileId)
    }

    @Test
    fun `direct failure falls through to the server chain`() {
        val edge = FakeEdge(ServerTtsResult.Unavailable(EdgeNeuralTtsClient.REASON_TRANSPORT))
        val voices = mutableListOf<String?>()
        val client = bridgeWithEdge(
            edge = edge,
            transport = ServerTtsTransport { _, _, request ->
                voices += request.voice
                ServerTtsResult.Audio(byteArrayOf(2), "audio/mpeg")
            },
        )

        val result = kotlinx.coroutines.runBlocking { client.synthesize("привет") }

        assertTrue("сокет упал — сервер обязан подхватить фразу", result is ServerTtsResult.Audio)
        assertEquals(listOf("ru-RU-SvetlanaNeural"), voices)
        assertEquals(1, edge.calls.size)
    }

    @Test
    fun `direct failure without a credential is an explicit unavailable`() {
        val edge = FakeEdge(ServerTtsResult.Unavailable(EdgeNeuralTtsClient.REASON_TRANSPORT))
        val client = bridgeWithEdge(edge = edge, credential = null)

        val result = kotlinx.coroutines.runBlocking { client.synthesize("привет") }

        assertTrue(result is ServerTtsResult.Unavailable)
        assertEquals(
            "unauthorized",
            (result as ServerTtsResult.Unavailable).reason,
        )
    }

    @Test
    fun `local voice never opens the socket`() {
        val edge = FakeEdge()
        var transportCalls = 0
        val client = bridgeWithEdge(
            edge = edge,
            prosody = VoiceProsody(voiceId = ServerTtsVoices.LOCAL_IRINA.id),
            transport = ServerTtsTransport { _, _, _ ->
                transportCalls++
                ServerTtsResult.Audio(byteArrayOf(3), "audio/wav")
            },
        )

        kotlinx.coroutines.runBlocking { client.synthesize("привет") }

        // piper — не edge-движок: сокет Microsoft для него бессмысленен.
        assertTrue("локальный голос не должен идти в Bing", edge.calls.isEmpty())
        assertEquals(1, transportCalls)
    }

    @Test
    fun `ava rejected by the server allowlist falls to the next web voice`() {
        val edge = FakeEdge(ServerTtsResult.Unavailable(EdgeNeuralTtsClient.REASON_TRANSPORT))
        val voices = mutableListOf<String?>()
        val client = RepositoryServerTtsClient(
            baseUrl = "https://marscore.ru",
            credentialProvider = { credential },
            prosody = VoiceProsody(voiceId = ServerTtsVoices.NATURAL_AVA.id),
            directEdgeClient = edge,
            transport = ServerTtsTransport { _, _, request ->
                voices += request.voice
                // Серверный allowlist знает только Светлану и Дмитрия: Ava получает 400.
                if (request.voice == "en-US-AvaMultilingualNeural") {
                    ServerTtsResult.Unavailable("http_400", 400)
                } else {
                    ServerTtsResult.Audio(byteArrayOf(4), "audio/mpeg")
                }
            },
        )

        val result = kotlinx.coroutines.runBlocking { client.synthesize("привет") }

        assertTrue("400 по Аве не должен съедать фразу", result is ServerTtsResult.Audio)
        // Выбранная Ава — первой (и в сокете, и на сервере), затем следующий живой голос.
        assertEquals("en-US-AvaMultilingualNeural", edge.calls.single().first)
        assertEquals(
            listOf("en-US-AvaMultilingualNeural", "ru-RU-SvetlanaNeural"),
            voices,
        )
        assertEquals("natural-svetlana", client.lastProfileId)
    }

    // --- Предсинтез (prefetch): фраза N+1 синтезируется, пока звучит фраза N --------------
    //
    // Требование: пауза 5–10 с после первой запятой возникала потому, что кусок N+1 уходил в
    // синтез ТОЛЬКО после тишины. Предсинтез убирает эту паузу: speak забирает готовый
    // результат и не делает второго запроса. Проверяется наблюдаемое — число запросов к
    // синтезу и число проигранных фрагментов.

    @Test
    fun `prefetched phrase is spoken without a second synthesize call`() {
        val client = FakeClient(audio)
        val playback = FakePlayback()
        val engine = engine(client = client, playback = playback)

        engine.prefetch("вторая фраза")
        assertEquals(1, engine.prefetchedCount)
        assertEquals(listOf("вторая фраза"), client.requests)

        assertTrue(engine.speak("вторая фраза"))

        // Ровно ОДИН запрос: speak забрал готовое аудио, а не синтезировал фразу заново.
        assertEquals(listOf("вторая фраза"), client.requests)
        assertEquals(1, playback.played.size)
        assertEquals(0, engine.prefetchedCount)
    }

    @Test
    fun `prefetch ignores blank and oversized text`() {
        val client = FakeClient(audio)
        val engine = engine(client = client)

        engine.prefetch("   ")
        engine.prefetch("")
        engine.prefetch("я".repeat(ServerTtsContract.MAX_TEXT_LENGTH + 1))

        // Пустой текст сервер отверг бы, а длинный — 400: предсинтез не создаёт этот запрос.
        assertEquals(0, engine.prefetchedCount)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `prefetch keeps at most three phrases ahead`() {
        val engine = engine(client = FakeClient(audio))

        engine.prefetch("раз")
        engine.prefetch("два")
        engine.prefetch("три")
        engine.prefetch("четыре")

        // Потолок очереди: дальше — синтез в пустоту (человек перебьёт ответ раньше).
        assertEquals(ServerTtsSpeechEngine.MAX_PREFETCH, engine.prefetchedCount)
    }

    @Test
    fun `the same phrase is prefetched only once`() {
        val client = FakeClient(audio)
        val engine = engine(client = client)

        engine.prefetch("повтор")
        engine.prefetch("повтор")

        assertEquals(1, engine.prefetchedCount)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `stop cancels prefetched work so silence costs no request`() {
        val engine = engine(client = FakeClient(audio))
        engine.prefetch("не доиграло")

        engine.stop()

        assertEquals("предсинтез отменён вместе с тишиной", 0, engine.prefetchedCount)
    }

    @Test
    fun `shutdown drops prefetched work too`() {
        val engine = engine(client = FakeClient(audio))
        engine.prefetch("не доиграло")

        engine.shutdown()

        assertEquals(0, engine.prefetchedCount)
    }

    @Test
    fun `prefetched failure still falls back to system tts`() {
        val system = FakeSystemTts()
        val client = FakeClient(ServerTtsResult.Unavailable("unauthorized", 401))
        val engine = engine(system, client)

        engine.prefetch("фраза")
        assertTrue(engine.speak("фраза"))

        // Отказ из предсинтеза — не повод синтезировать заново: фраза уходит в системный TTS.
        assertEquals(listOf("фраза"), system.spoken)
        assertEquals(1, client.requests.size)
    }

    // --- Платный облачный голос (OpenAI/PlusVibe) и нейро-фолбэк --------------------------
    //
    // Требование: выбранный «Оникс» не должен падать в механический системный TTS, даже если
    // ключа нет или апстрим PlusVibe завис. Проверяется наблюдаемое: кто реально синтезировал.

    /** Мост с платным клиентом и (опционально) прямым сокетом. */
    private fun bridgeWithPaid(
        paid: PaidTtsClient?,
        edge: EdgeNeuralTtsClient? = FakeEdge(),
        prosody: VoiceProsody = VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_ONYX.id),
        /** Бюджет ожидания платного голоса: в тестах короткий, чтобы не ждать 8 с. */
        paidWaitBudget: (ServerTtsVoiceProfile) -> Long? = { it.waitBudgetMs },
        transport: ServerTtsTransport = ServerTtsTransport { _, _, _ ->
            ServerTtsResult.Unavailable("server_error", 503)
        },
    ) = RepositoryServerTtsClient(
        baseUrl = "https://marscore.ru",
        credentialProvider = { credential },
        prosody = prosody,
        directEdgeClient = edge,
        paidClient = paid,
        paidWaitBudget = paidWaitBudget,
        transport = transport,
    )

    @Test
    fun `paid voice answers and the edge socket stays silent`() {
        val paid = FakeOpenAi()
        val edge = FakeEdge()
        val client = bridgeWithPaid(paid = paid, edge = edge)

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue(result is ServerTtsResult.Audio)
        assertEquals(listOf(Triple("фраза", "onyx", 1.0f)), paid.calls)
        assertTrue("при живом платном голосе сокет не нужен", edge.calls.isEmpty())
        assertEquals("plusvibe-onyx", client.lastProfileId)
    }

    @Test
    fun `paid voice receives the person tempo as speed`() {
        val paid = FakeOpenAi()
        val client = bridgeWithPaid(
            paid = paid,
            prosody = VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_ONYX.id, speed = 1.15f),
        )

        kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        // У провайдера темп — множитель, а не «+15%»: ползунок обязан доехать как 1.15.
        assertEquals(1.15f, paid.calls.single().third, 0.001f)
    }

    @Test
    fun `paid voice timeout falls back to the edge neural voice instead of the robot`() {
        val paid = FakeOpenAi(ServerTtsResult.Unavailable(OpenAiSpeechTtsClient.REASON_TRANSPORT))
        val edge = FakeEdge()
        val client = bridgeWithPaid(paid = paid, edge = edge)

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue("отказ платного голоса не должен съедать фразу", result is ServerTtsResult.Audio)
        // Резерв — нейросетевой Дмитрий, а не системный робот: тембр остаётся живым.
        assertEquals(listOf(Triple("ru-RU-DmitryNeural", "+5%", "+0Hz")), edge.calls)
        assertEquals("natural-dmitry", client.lastProfileId)
        assertEquals(
            listOf("plusvibe-onyx" to OpenAiSpeechTtsClient.REASON_TRANSPORT, "natural-dmitry" to null),
            client.lastAttempts.map { it.profileId to it.reason },
        )
    }

    @Test
    fun `paid voice without a configured client is a neural fallback`() {
        val edge = FakeEdge()
        // paidClient по умолчанию null — так вызывается конструктор во всём старом коде.
        val client = bridgeWithPaid(paid = null, edge = edge)

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue(result is ServerTtsResult.Audio)
        assertEquals("ru-RU-DmitryNeural", edge.calls.single().first)
        assertEquals("natural-dmitry", client.lastProfileId)
    }

    // --- Русский голос из библиотеки: `voice_id` и бюджет ожидания -------------------------
    //
    // Живой дефект канала (18.11.2026): задача с `voice_id` принимается (`202`, id в
    // `effectiveOpts`) и НЕ завершается — 5 из 5 попыток висели в `processing` через 20–25 с,
    // тогда как контрольный вызов с `voice:"Alice"` в ту же минуту дал аудио. Требование:
    // такая реплика не молчит транспортные 90 с, а меняет голос на нейро-резерв.

    /** Платный клиент, который «принял заявку и завис»: ровно поведение `voice_id`. */
    private class HangingPaid : PaidTtsClient {
        var calls: Int = 0
            private set

        override suspend fun synthesize(
            text: String,
            voice: String,
            model: String,
            speed: Float,
            instructions: String?,
            voiceId: String?,
        ): ServerTtsResult {
            calls++
            // Задача не завершается никогда: единственный выход — бюджет ожидания вызывающего.
            kotlinx.coroutines.delay(Long.MAX_VALUE)
            return ServerTtsResult.Unavailable("unreachable")
        }
    }

    @Test
    fun `library voice travels to the paid client as a voice id, not as a name`() {
        val paid = FakeOpenAi()
        val client = bridgeWithPaid(
            paid = paid,
            prosody = VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_LARISA.id),
        )

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue(result is ServerTtsResult.Audio)
        // В провайдера уезжает именно id: имени `Larisa` нет в перечислении 21 голоса.
        assertEquals(listOf<String?>(ServerTtsVoices.LIBRARY_VOICE_LARISA), paid.voiceIds)
        assertEquals("Larisa", paid.calls.single().second)
        assertEquals("plusvibe-larisa", client.lastProfileId)
    }

    @Test
    fun `hung library voice is cut by its budget and read by the neural reserve`() {
        val paid = HangingPaid()
        val edge = FakeEdge()
        val client = bridgeWithPaid(
            paid = paid,
            edge = edge,
            prosody = VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_LARISA.id),
            // 40 мс вместо боевых 8 с: проверяется ПОЛИТИКА, а не длительность.
            paidWaitBudget = { 40L },
        )

        val startedAt = System.currentTimeMillis()
        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }
        val elapsedMs = System.currentTimeMillis() - startedAt

        // Таймаут не теряет фразу: её читает нейро-голос, и подмена объявляется причиной.
        assertTrue("фраза обязана прозвучать резервом", result is ServerTtsResult.Audio)
        assertEquals(listOf("ru-RU-DmitryNeural"), edge.calls.map { it.first })
        assertEquals("natural-dmitry", client.lastProfileId)
        assertEquals(
            "истечение бюджета — своя причина, отличная от таймаута транспорта",
            listOf(
                "plusvibe-larisa" to RepositoryServerTtsClient.REASON_PAID_DEADLINE,
                "natural-dmitry" to null,
            ),
            client.lastAttempts.map { it.profileId to it.reason },
        )
        assertTrue("ожидание обязано оборваться по бюджету: ${elapsedMs}мс", elapsedMs < 5_000)
    }

    @Test
    fun `named voice has no budget and is not cut while the provider is merely slow`() {
        // У `Alice`/`onyx` бюджета нет: апстрим под нагрузкой честно отвечает за 13 с, и
        // обрывать живой платный запрос значило бы заплатить дважды за одну фразу.
        assertNull(ServerTtsVoices.PLUSVIBE_ALICE.waitBudgetMs)

        val paid = FakeOpenAi()
        val client = bridgeWithPaid(
            paid = paid,
            prosody = VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_ALICE.id),
            // Бюджет есть, но профиль его не объявляет — значит он и не применяется.
            paidWaitBudget = { it.waitBudgetMs },
        )

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue(result is ServerTtsResult.Audio)
        assertEquals("plusvibe-alice", client.lastProfileId)
        assertEquals(listOf<String?>(null), paid.voiceIds)
    }

    @Test
    fun `paid profile is never sent to the mars server`() {
        val engines = mutableListOf<String?>()
        val client = RepositoryServerTtsClient(
            baseUrl = "https://marscore.ru",
            credentialProvider = { credential },
            prosody = VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_ONYX.id),
            directEdgeClient = null,
            paidClient = null,
            transport = ServerTtsTransport { _, _, request ->
                engines += request.engine
                ServerTtsResult.Audio(byteArrayOf(1), "audio/mpeg")
            },
        )

        kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        // Сервер не знает голос `onyx`: запрос с engine=openai был бы гарантированным 400.
        assertEquals(listOf("edge"), engines)
        assertEquals("natural-svetlana", client.lastProfileId)
    }

    // --- Резерв: КТО именно заговорил после отказа облака ---------------------------------
    //
    // Требование: при отказе облака фразу произносит ЛОКАЛЬНАЯ модель, а не системный робот;
    // если модели на устройстве нет вовсе — говорит платформа, но событие подмены уходит хосту.
    // Проверяется наблюдаемое: что попало в резервный движок и какое событие получил хост.

    @Test
    fun `offline reserve speaks after a cloud failure and reports its channel`() {
        val piper = FakeSystemTts()
        val events = mutableListOf<VoiceSubstitution>()
        val engine = engine(
            system = piper,
            client = FakeClient(ServerTtsResult.Unavailable("unauthorized", 401)),
            fallbackChannel = VoiceChannel.OFFLINE,
            onVoiceSubstituted = { events += it },
        )

        assertTrue(engine.speak("фраза"))

        assertEquals("отказ облака обязан звучать локальной моделью", listOf("фраза"), piper.spoken)
        assertEquals(
            listOf(VoiceSubstitution(VoiceChannel.OFFLINE, VoiceSubstitutionReason.CLOUD_FAILED)),
            events,
        )
    }

    @Test
    fun `platform reserve reports the system channel so the substitution is visible`() {
        val events = mutableListOf<VoiceSubstitution>()
        val engine = engine(
            client = FakeClient(ServerTtsResult.Unavailable("unauthorized", 401)),
            fallbackChannel = VoiceChannel.SYSTEM,
            onVoiceSubstituted = { events += it },
        )

        assertTrue(engine.speak("фраза"))

        assertEquals(
            listOf(VoiceSubstitution(VoiceChannel.SYSTEM, VoiceSubstitutionReason.SYSTEM_RESERVE)),
            events,
        )
    }

    @Test
    fun `oversized text goes straight to the reserve and is reported`() {
        val system = FakeSystemTts()
        val events = mutableListOf<VoiceSubstitution>()
        val engine = engine(
            system = system,
            client = FakeClient(audio),
            fallbackChannel = VoiceChannel.OFFLINE,
            onVoiceSubstituted = { events += it },
        )
        val tooLong = "я".repeat(ServerTtsContract.MAX_TEXT_LENGTH + 1)

        assertTrue(engine.speak(tooLong))

        assertEquals(0, engine.serverAttemptCount)
        assertEquals(listOf(tooLong), system.spoken)
        assertEquals(1, events.size)
    }

    @Test
    fun `a phrase stopped by the user never speaks through the reserve afterwards`() {
        val system = FakeSystemTts()
        val playback = FakePlayback()
        val engine = engine(system, FakeClient(audio), playback)

        engine.speak("отменённая фраза")
        engine.stop()
        // Поздний отказ декодера приходит уже после стопа: реплика отменена и звучать не должна.
        playback.failAsync()

        assertTrue("после стопа реплика не догоняет пользователя", system.spoken.isEmpty())
    }

    @Test
    fun `a stop during the network attempt keeps the phrase silent`() {
        val system = FakeSystemTts()
        // Держатель вместо lateinit: клиент обращается к движку, который создаётся после него.
        val holder = arrayOfNulls<ServerTtsSpeechEngine>(1)
        val client = object : ServerTtsClient {
            override suspend fun synthesize(text: String): ServerTtsResult {
                // Человек нажал микрофон, пока шёл синтез: стоп приходит публичным путём,
                // то есть ровно так, как это делает очередь озвучки.
                holder[0]?.stop()
                return ServerTtsResult.Unavailable("transport")
            }
        }
        val engine = ServerTtsSpeechEngine(
            fallback = system,
            client = client,
            playback = FakePlayback(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        holder[0] = engine

        assertTrue(engine.speak("отменённая фраза"))

        assertTrue("отменённую реплику резерв озвучивать не должен", system.spoken.isEmpty())
    }

    @Test
    fun `a phrase after a stop still reaches the reserve`() {
        val system = FakeSystemTts()
        val client = FakeClient(ServerTtsResult.Unavailable("unauthorized", 401))
        val engine = engine(system, client)

        engine.stop()

        // Счётчик поколения не глушит движок навсегда: следующая реплика звучит штатно.
        assertTrue(engine.speak("следующая фраза"))
        assertEquals(listOf("следующая фраза"), system.spoken)
    }

    @Test
    fun `a phrase nobody could speak is reported as silent`() {
        var silent = 0
        val engine = engine(
            // Резерв мёртв (нет ни модели, ни платформенного движка), облако отказало.
            system = FakeSystemTts(accept = false),
            client = FakeClient(ServerTtsResult.Unavailable("unauthorized", 401)),
            onSilent = { silent++ },
        )

        assertTrue("спикер принят оптимистично", engine.speak("фраза"))

        // Без этого сигнала хост навсегда остался бы в фазе «говорю»: speak ответил true,
        // а колбэка «дочитал» не будет ни от одного канала.
        assertEquals(1, silent)
    }

    @Test
    fun `a phrase that did reach the reserve is not reported as silent`() {
        var silent = 0
        val engine = engine(
            client = FakeClient(ServerTtsResult.Unavailable("unauthorized", 401)),
            onSilent = { silent++ },
        )

        assertTrue(engine.speak("фраза"))

        assertEquals(0, silent)
    }

    // --- Выбор голоса доезжает до синтеза -------------------------------------------------
    //
    // Требование Волны 22: голос, выбранный в шторке, обязан ЗВУЧАТЬ, а не только
    // подсвечиваться. Дефект был двойной: `setProsody` движка переливал значение в резерв и
    // забывал его (клиент синтеза узнавал о голосе только через приведение типа в
    // `LocalVoiceTts`), а кэш предсинтеза индексировался одним текстом, поэтому остаток
    // ответа доигрывал прежним голосом.

    @Test
    fun `engine prosody reaches the synthesis client so the chosen voice is requested`() {
        val voices = mutableListOf<String?>()
        val client = bridge { _, _, request ->
            voices += request.voice
            ServerTtsResult.Audio(byteArrayOf(1), "audio/mpeg")
        }
        val engine = engine(client = client, playback = FakePlayback())

        engine.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_DMITRY.id))
        assertTrue(engine.speak("фраза"))

        // Без выбора движок попросил бы Светлану (первый бесплатный профиль). Дмитрий в
        // запросе — доказательство, что настройка доехала до синтеза, а не осталась в шторке.
        assertEquals(listOf("ru-RU-DmitryNeural"), voices)
        assertEquals(ServerTtsVoices.NATURAL_DMITRY.id, engine.currentProsody?.voiceId)
    }

    @Test
    fun `prefetched phrase is not served after the voice changed`() {
        val voices = mutableListOf<String?>()
        val client = bridge { _, _, request ->
            voices += request.voice
            ServerTtsResult.Audio(byteArrayOf(1), "audio/mpeg")
        }
        val playback = FakePlayback()
        val engine = engine(client = client, playback = playback)

        engine.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_DMITRY.id))
        engine.prefetch("фраза")
        assertEquals(1, engine.prefetchedCount)

        engine.setProsody(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id))

        // Кэш прежнего голоса отменён: иначе фраза зазвучала бы Дмитрием после того, как
        // человек выбрал Светлану, — то есть выбор снова был бы только визуальным.
        assertEquals(0, engine.prefetchedCount)
        assertTrue(engine.speak("фраза"))
        assertEquals(listOf("ru-RU-DmitryNeural", "ru-RU-SvetlanaNeural"), voices)
        assertEquals(1, playback.played.size)
    }

    @Test
    fun `tempo change also drops the prefetched phrase synthesized at the old rate`() {
        val rates = mutableListOf<String?>()
        val client = bridge { _, _, request ->
            rates += request.rate
            ServerTtsResult.Audio(byteArrayOf(1), "audio/mpeg")
        }
        val engine = engine(client = client, playback = FakePlayback())

        engine.setProsody(VoiceProsody(speed = 1.15f))
        engine.prefetch("фраза")
        engine.setProsody(VoiceProsody(speed = 0.9f))

        // Темп уезжает в том же запросе, что и голос, поэтому устаревший предсинтез — это
        // фраза, прочитанная не тем темпом, который человек только что выставил.
        assertEquals(0, engine.prefetchedCount)
        assertTrue(engine.speak("фраза"))
        assertEquals(listOf("+15%", "-10%"), rates)
    }

    @Test
    fun `paid voice replaced by the server chain is announced instead of silently swapped`() {
        val reasons = mutableListOf<String?>()
        val voices = mutableListOf<String?>()
        val client = RepositoryServerTtsClient(
            baseUrl = "https://marscore.ru",
            credentialProvider = { credential },
            selector = SpeechVoiceProfileSelector(),
            prosody = VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_ALICE.id),
            directEdgeClient = null,
            // Платный клиент не подключён: ровно тот случай, когда «Алиса» молча звучала
            // Дмитрием, а если и он не ответил — Светланой.
            paidClient = null,
            transport = ServerTtsTransport { _, _, request ->
                voices += request.voice
                ServerTtsResult.Audio(byteArrayOf(1), "audio/mpeg")
            },
            onPaidUnavailable = { reason -> reasons += reason },
        )

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue("фразу обязан прочитать резерв, а не потерять", result is ServerTtsResult.Audio)
        // Звук есть — но это НЕ выбранный голос, и человек обязан узнать об этом.
        assertEquals(listOf("ru-RU-SvetlanaNeural"), voices)
        assertEquals(listOf<String?>(RepositoryServerTtsClient.REASON_NO_PAID_CLIENT), reasons)
    }

    @Test
    fun `chosen voice demoted inside the chain reports wanted and actual profile ids`() {
        val events = mutableListOf<Triple<String?, String, String?>>()
        val client = RepositoryServerTtsClient(
            baseUrl = "https://marscore.ru",
            credentialProvider = { credential },
            selector = SpeechVoiceProfileSelector(),
            prosody = VoiceProsody(voiceId = ServerTtsVoices.NATURAL_DMITRY.id),
            directEdgeClient = null,
            transport = ServerTtsTransport { _, _, request ->
                if (request.voice == ServerTtsVoices.NATURAL_DMITRY.voice) {
                    ServerTtsResult.Unavailable("invalid_voice", 400)
                } else {
                    ServerTtsResult.Audio(byteArrayOf(1), "audio/mpeg")
                }
            },
            onVoiceSubstituted = { wanted, actual, reason -> events += Triple(wanted, actual, reason) },
        )

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertTrue(result is ServerTtsResult.Audio)
        // Аудио вернулось штатно, поэтому движок увидел бы «успех» и промолчал: без этого
        // события «выбрал Дмитрия — услышал Светлану» осталось бы загадкой.
        assertEquals(
            listOf<Triple<String?, String, String?>>(
                Triple(ServerTtsVoices.NATURAL_DMITRY.id, ServerTtsVoices.NATURAL_SVETLANA.id, "invalid_voice"),
            ),
            events,
        )
    }
}
