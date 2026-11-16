package ru.mars.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ServerTtsContract
import ru.mars.network.repository.ServerTtsResult
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
        client: FakeClient = FakeClient(),
        playback: FakePlayback = FakePlayback(),
        onServerAudioAvailable: () -> Unit = {},
    ) = ServerTtsSpeechEngine(
        fallback = system,
        client = client,
        playback = playback,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onServerAudioAvailable = onServerAudioAvailable,
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

    // --- Мост «сеть + credential» ---------------------------------------------------------

    @Test
    fun `missing credential is a fallback request, not an unauthenticated call`() {
        var transportCalls = 0
        val client = RepositoryServerTtsClient(
            baseUrl = "https://marscore.ru",
            credentialProvider = { null },
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
        assertEquals("+10%", seenRate)
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
        transport: ServerTtsTransport,
    ) = RepositoryServerTtsClient(
        baseUrl = "https://marscore.ru",
        credentialProvider = { credential },
        selector = selector,
        transport = transport,
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
        assertEquals(listOf("edge", "edge", "piper"), engines)
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
        assertEquals(listOf("edge", "edge", "piper"), engines)
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

        assertEquals("цепочка должна быть пройдена целиком", listOf("edge", "edge", "piper"), engines)
        assertTrue("исчерпанная цепочка — это ошибка, а не пустой ответ", result is ServerTtsResult.Unavailable)
        assertEquals("profiles_exhausted", (result as ServerTtsResult.Unavailable).reason)
    }

    @Test
    fun `rate limit is not a reason to switch the voice`() {
        val engines = mutableListOf<String?>()
        val client = bridge { _, _, request ->
            engines += request.engine
            ServerTtsResult.Unavailable("rate_limited", 429)
        }

        val result = kotlinx.coroutines.runBlocking { client.synthesize("фраза") }

        assertEquals("лимитер общий для всех голосов — второй запрос бесполезен", listOf("edge"), engines)
        assertEquals("rate_limited", (result as ServerTtsResult.Unavailable).reason)
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
        assertEquals(listOf("edge", "edge", "piper"), engines)

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
        assertEquals(
            listOf("natural-svetlana" to 503, "natural-dmitry" to 503, "local-irina" to 400),
            client.lastAttempts.map { it.profileId to it.httpCode },
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
            listOf("ru-RU-SvetlanaNeural", "ru-RU-DmitryNeural", "ru_RU-irina-medium"),
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
        assertEquals(listOf("edge", "edge", "piper"), engines)
        assertEquals("profiles_exhausted", (first as ServerTtsResult.Unavailable).reason)

        // Человек выбрал Ирину руками: паузы сняты, и после её отказа сетевые голоса снова
        // получают попытку (без `reset()` они бы ещё висели на кулдауне и не запрашивались).
        client.setProsody(VoiceProsody(voiceId = ServerTtsVoices.LOCAL_IRINA.id))
        engines.clear()
        kotlinx.coroutines.runBlocking { client.synthesize("два") }

        assertEquals(listOf("piper", "edge", "edge"), engines)
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
}
