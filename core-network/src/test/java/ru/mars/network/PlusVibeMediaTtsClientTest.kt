package ru.mars.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.network.repository.PaidTtsClient
import ru.mars.network.repository.PlusVibeMediaTtsClient
import ru.mars.network.repository.ServerTtsResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Контракт асинхронного синтеза PlusVibe: `POST /api/media/generate` → поллинг задачи →
 * скачивание готового аудио.
 *
 * Проверяется наблюдаемое поведение клиента, а не внутренняя структура:
 * - какие пути и заголовки уходят в сеть (POST и jobs — с Bearer, скачивание — без него);
 * - что тело запроса несёт модель, промпт, `opts` и предохранитель `max_price_rub`;
 * - что `resultUrls` разбираются до НАСТОЯЩЕГО аудио: служебный JSON и HTML не отдаются как
 *   `audio/mpeg`, а подписанная форма ссылки без расширения всё равно находится;
 * - что текст режется по лимиту ВЫБРАННОЙ модели (4096 / 5000 / 15000), а не по одному на всех;
 * - что ключ не уходит на открытый `http` и не повторяется на публичную ссылку;
 * - что отказ провайдера превращается в [ServerTtsResult.Unavailable] с КОРОТКОЙ причиной,
 *   а не в исключение;
 * - что ожидание задачи ограничено и ОТМЕНЯЕМО (перебивание рвёт вызов, а не ждёт шаг опроса);
 * - что `/v1` в базе не ломает media-пути.
 *
 * Сеть подменена [MockWebServer]: настоящий провайдер не нужен, ключ не печатается.
 */
class PlusVibeMediaTtsClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun audio(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "audio/mpeg")
        .setBody(Buffer().write(bytes))

    /** Значение поля `prompt` из тела запроса (в тестах текст без экранируемых символов). */
    private fun promptOf(body: String): String =
        Regex("\"prompt\":\"(.*?)\",\"opts\"", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: throw AssertionError("в теле нет prompt: $body")

    /** Клиент, у которого опрос не спит: тесты не должны ждать реальные 1,2 с на шаг. */
    private fun client(
        baseUrl: String = server.url("/").toString(),
        apiKey: String = "sk-test",
        maxWaitMs: Long = 90_000L,
        nowMs: () -> Long = { 0L },
    ) = PlusVibeMediaTtsClient(
        baseUrlProvider = { baseUrl },
        apiKeyProvider = { apiKey },
        httpClient = OkHttpClient(),
        pollIntervalMs = 1L,
        maxWaitMs = maxWaitMs,
        sleeper = { },
        nowMs = nowMs,
    )

    // --- успешный путь ---------------------------------------------------------------------

    @Test
    fun `job is created polled and downloaded with the provider key only where needed`() {
        val jobId = "job-1"
        val audioUrl = server.url("/media/s3/results/2026/11/18/0.mp3").toString()
        server.enqueue(json(202, """{"ok":true,"jobId":"$jobId","status":"processing","priceRub":0}"""))
        server.enqueue(json(200, """{"status":"processing"}"""))
        server.enqueue(
            json(
                200,
                """{"status":"success","priceRub":1.14,"resultUrls":["$audioUrl"]}""",
            ),
        )
        server.enqueue(audio())

        val result = runBlocking { client().synthesize("Добрый день!", "Alice") }

        val played = result as ServerTtsResult.Audio
        assertEquals("audio/mpeg", played.contentType)
        assertEquals(listOf<Byte>(1, 2, 3, 4), played.bytes.toList())

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/media/generate", create.path)
        assertEquals("Bearer sk-test", create.getHeader("Authorization"))

        val body = create.body.readUtf8()
        assertTrue(body, body.contains("\"model\":\"elevenlabs-v4-tts\""))
        assertTrue(body, body.contains("\"prompt\":\"Добрый день!\""))
        assertTrue(body, body.contains("\"voice\":\"Alice\""))
        assertTrue(body, body.contains("\"language_code\":\"ru\""))
        assertTrue(body, body.contains("\"output_format\":\"mp3\""))
        // `engine` — только у ElevenLabs: у других моделей это лишний опт.
        assertTrue(body, body.contains("\"engine\":\"turbo\""))
        // Предохранитель цены уходит всегда: отказ по нему бесплатный (422 до запуска).
        assertTrue(body, body.contains("\"max_price_rub\":30.00"))

        val poll = server.takeRequest()
        assertEquals("GET", poll.method)
        assertEquals("/api/media/jobs/$jobId", poll.path)
        assertEquals("Bearer sk-test", poll.getHeader("Authorization"))

        val secondPoll = server.takeRequest()
        assertEquals("/api/media/jobs/$jobId", secondPoll.path)

        // Ссылка из `resultUrls` ПУБЛИЧНАЯ: ключ при скачивании не отправляется.
        val download = server.takeRequest()
        assertEquals("/media/s3/results/2026/11/18/0.mp3", download.path)
        assertNull("ключ не должен уходить на публичную ссылку", download.getHeader("Authorization"))
    }

    @Test
    fun `audio returned by the create call skips polling entirely`() {
        val audioUrl = server.url("/media/s3/results/2026/11/18/0.mp3").toString()
        server.enqueue(
            json(
                200,
                """{"ok":true,"jobId":"job-2","status":"success","resultUrls":["$audioUrl"]}""",
            ),
        )
        server.enqueue(audio(byteArrayOf(0x49, 0x44, 0x33)))

        val result = runBlocking { client().synthesize("короткая фраза", "Alice") }

        assertTrue(result is ServerTtsResult.Audio)
        // Второй запрос — скачивание, а не опрос задачи.
        server.takeRequest()
        assertEquals("/media/s3/results/2026/11/18/0.mp3", server.takeRequest().path)
    }

    @Test
    fun `non audio entries in resultUrls are skipped`() {
        val wordsUrl = server.url("/media/s3/results/2026/11/18/1.json").toString()
        val audioUrl = server.url("/media/s3/results/2026/11/18/0.mp3").toString()
        server.enqueue(json(202, """{"ok":true,"jobId":"job-3","status":"processing"}"""))
        server.enqueue(
            json(200, """{"status":"success","resultUrls":["$wordsUrl","$audioUrl"]}"""),
        )
        server.enqueue(audio(byteArrayOf(7)))

        val result = runBlocking { client().synthesize("фраза", "Alice") }

        assertTrue("аудио обязано найтись среди ссылок", result is ServerTtsResult.Audio)
        server.takeRequest()
        server.takeRequest()
        assertEquals(
            "скачивается именно аудио, а не JSON со словами",
            "/media/s3/results/2026/11/18/0.mp3",
            server.takeRequest().path,
        )
    }

    /**
     * Подписанная форма `resultUrls` (`…/api/media/file/<id>/0?sig=…`) НЕ имеет расширения.
     *
     * Живой контракт: в массиве первым может прийти JSON со словами и таймкодами. Старый
     * выбор «первая ссылка, похожая на аудио, иначе первая вообще» скачивал JSON и отдавал
     * его как `audio/mpeg` — проигрыватель получал мусор. Теперь не-аудио ответ бракуется, и
     * берётся следующая ссылка.
     */
    @Test
    fun `signed result urls are tried until real audio arrives`() {
        val wordsUrl = server.url("/api/media/file/abc/1?sig=w").toString()
        val audioUrl = server.url("/api/media/file/abc/0?sig=a").toString()
        server.enqueue(json(202, """{"ok":true,"jobId":"job-8","status":"processing"}"""))
        server.enqueue(json(200, """{"status":"success","resultUrls":["$wordsUrl","$audioUrl"]}"""))
        server.enqueue(json(200, """{"text":"Добрый день","words":[]}"""))
        server.enqueue(audio(byteArrayOf(5, 6)))

        val result = runBlocking { client().synthesize("Добрый день", "Alice") }

        val played = result as ServerTtsResult.Audio
        assertEquals(listOf<Byte>(5, 6), played.bytes.toList())
        assertEquals("audio/mpeg", played.contentType)

        server.takeRequest() // POST generate
        server.takeRequest() // GET jobs
        assertEquals("/api/media/file/abc/1?sig=w", server.takeRequest().path)
        assertEquals("/api/media/file/abc/0?sig=a", server.takeRequest().path)
    }

    @Test
    fun `a json payload is never returned as audio`() {
        val wordsUrl = server.url("/media/s3/results/2026/11/18/1.json").toString()
        server.enqueue(json(202, """{"ok":true,"jobId":"job-9","status":"processing"}"""))
        server.enqueue(json(200, """{"status":"success","resultUrls":["$wordsUrl"]}"""))
        server.enqueue(json(200, """{"words":[]}"""))

        val result = runBlocking { client().synthesize("фраза", "Alice") }

        assertEquals("empty_result", (result as ServerTtsResult.Unavailable).reason)
    }

    @Test
    fun `an oversized body is refused instead of being loaded into memory`() {
        val audioUrl = server.url("/media/s3/results/2026/11/18/0.mp3").toString()
        server.enqueue(json(202, """{"ok":true,"jobId":"job-11","status":"processing"}"""))
        server.enqueue(json(200, """{"status":"success","resultUrls":["$audioUrl"]}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(ByteArray((PlusVibeMediaTtsClient.MAX_RESPONSE_BYTES + 1).toInt()))),
        )

        val result = runBlocking { client().synthesize("фраза", "Alice") }

        assertEquals("empty_result", (result as ServerTtsResult.Unavailable).reason)
    }

    // --- отказы ----------------------------------------------------------------------------

    @Test
    fun `price cap refusal is a short reason and never polls`() {
        server.enqueue(
            json(
                422,
                """{"ok":false,"code":"media_max_price_exceeded","message":"Стоимость больше лимита"}""",
            ),
        )

        val result = runBlocking { client().synthesize("фраза", "Alice") }

        val unavailable = result as ServerTtsResult.Unavailable
        assertEquals("media_max_price_exceeded", unavailable.reason)
        assertEquals(422, unavailable.httpCode)
        assertEquals("второго запроса быть не должно", 1, server.requestCount)
    }

    @Test
    fun `failed job reports the provider error code`() {
        server.enqueue(json(202, """{"ok":true,"jobId":"job-4","status":"processing"}"""))
        server.enqueue(
            json(
                200,
                """{"status":"failed","errorCode":"media_generation_failed","failMsg":"engine down"}""",
            ),
        )

        val result = runBlocking { client().synthesize("фраза", "Alice") }

        val unavailable = result as ServerTtsResult.Unavailable
        assertEquals("media_generation_failed", unavailable.reason)
    }

    @Test
    fun `job that never finishes times out instead of hanging`() {
        server.enqueue(json(202, """{"ok":true,"jobId":"job-5","status":"processing"}"""))
        server.enqueue(json(200, """{"status":"processing"}"""))

        // Потолок ожидания уже истёк: первый же опрос обязан дать timeout.
        val result = runBlocking { client(maxWaitMs = 0L).synthesize("фраза", "Alice") }

        assertEquals("timeout", (result as ServerTtsResult.Unavailable).reason)
    }

    @Test
    fun `success without any result url is an explicit empty result`() {
        server.enqueue(json(202, """{"ok":true,"jobId":"job-6","status":"processing"}"""))
        server.enqueue(json(200, """{"status":"success","resultUrls":[]}"""))

        val result = runBlocking { client().synthesize("фраза", "Alice") }

        assertEquals("empty_result", (result as ServerTtsResult.Unavailable).reason)
    }

    @Test
    fun `missing key never touches the network`() {
        val result = runBlocking { client(apiKey = "  ").synthesize("фраза", "Alice") }

        assertEquals("no_api_key", (result as ServerTtsResult.Unavailable).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `empty text is refused before the request`() {
        val result = runBlocking { client().synthesize("   ", "Alice") }

        assertEquals("empty_text", (result as ServerTtsResult.Unavailable).reason)
        assertEquals(0, server.requestCount)
    }

    /**
     * Живой контракт: `401` приходит ПЛОСКИМ текстом `invalid api key`, а не JSON.
     * Клиент обязан отдать короткую причину и не пересказывать тело (в теле может оказаться
     * что угодно, включая фрагмент ключа).
     */
    @Test
    fun `a plain text 401 becomes unauthorized without echoing the body`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid api key"))

        val result = runBlocking { client().synthesize("фраза", "Alice") }

        val unavailable = result as ServerTtsResult.Unavailable
        assertEquals("unauthorized", unavailable.reason)
        assertEquals(401, unavailable.httpCode)
        assertFalse("ключ не должен попасть в причину", unavailable.reason.contains("sk-test"))
        assertEquals("платный вызов не повторяется: каждый POST — новая задача и новые деньги", 1, server.requestCount)
    }

    @Test
    fun `an ok false job reply fails fast instead of polling to the deadline`() {
        server.enqueue(json(202, """{"ok":true,"jobId":"job-10","status":"processing"}"""))
        server.enqueue(json(200, """{"ok":false,"code":"media_result_expired"}"""))

        val result = runBlocking { client().synthesize("фраза", "Alice") }

        assertEquals("media_result_expired", (result as ServerTtsResult.Unavailable).reason)
        assertEquals("опрос обязан прекратиться сразу", 2, server.requestCount)
    }

    @Test
    fun `a cleartext base is refused before the key leaves the device`() {
        val result = runBlocking {
            client(baseUrl = "http://plusvibeapi.ru/v1").synthesize("фраза", "Alice")
        }

        assertEquals("insecure_base_url", (result as ServerTtsResult.Unavailable).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `transport failure is unavailable not an exception`() {
        // Сервер остановлен: сокет не поднимется.
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/").toString()
        dead.shutdown()

        val result = runBlocking { client(baseUrl = url).synthesize("фраза", "Alice") }

        assertEquals("transport", (result as ServerTtsResult.Unavailable).reason)
    }

    // --- отмена ----------------------------------------------------------------------------

    /**
     * Отмена обязана РВАТЬ висящий запрос, а не дожидаться шага опроса.
     *
     * Почему тест устроен именно так. Прежняя версия проверяла
     * `withTimeoutOrNull(200) { synthesize() }` — такой вызов возвращает `null` при любом
     * таймауте, даже если клиент игнорирует отмену целиком: проверка была вакуумной.
     *
     * Вторая ловушка, уже неочевидная: `suspendCancellableCoroutine` умирает по отмене САМ,
     * без `Call.cancel()`, поэтому «корутина завершилась быстро» ничего не доказывает —
     * сокет при этом остаётся висеть до `readTimeout`. Настоящий признак — событие
     * [EventListener.canceled]: его поднимает только реальный `Call.cancel()`. Сервер здесь
     * не отвечает вовсе (`NO_RESPONSE`), так что другого способа закрыть вызов нет.
     */
    @Test(timeout = 15_000)
    fun `cancellation aborts the hanging call instead of waiting for the poll step`() {
        server.enqueue(json(202, """{"ok":true,"jobId":"job-7","status":"processing"}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val callCancelled = AtomicBoolean(false)
        val http = OkHttpClient.Builder()
            .eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        callCancelled.set(true)
                    }
                },
            )
            .build()

        val client = PlusVibeMediaTtsClient(
            baseUrlProvider = { server.url("/").toString() },
            apiKeyProvider = { "sk-test" },
            httpClient = http,
            pollIntervalMs = 30_000L,
            maxWaitMs = 90_000L,
            // Настоящее ожидание: отменить его обязана корутина, а не тестовый фейк.
            sleeper = { millis -> delay(millis) },
        )

        runBlocking {
            val job = launch(Dispatchers.IO) { client.synthesize("фраза", "Alice") }
            // Оба запроса должны реально уйти в сеть: отменять имеет смысл только висящий вызов.
            val waitUntil = System.currentTimeMillis() + 5_000
            while (server.requestCount < 2 && System.currentTimeMillis() < waitUntil) delay(20)
            assertEquals("POST и GET задачи должны уйти до отмены", 2, server.requestCount)

            val startedAt = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("отмена не должна ждать шаг опроса: ${elapsedMs}мс", elapsedMs < 2_000)

            val eventUntil = System.currentTimeMillis() + 2_000
            while (!callCancelled.get() && System.currentTimeMillis() < eventUntil) delay(10)
            assertTrue(
                "Call.cancel() обязан оборвать висящий запрос, а не оставить сокет до readTimeout",
                callCancelled.get(),
            )
            assertTrue("корутина обязана умереть, а не вернуть отказ", job.isCancelled)
        }
    }

    // --- чистые правила контракта ----------------------------------------------------------

    @Test
    fun `media root drops the openai style v1 segment but keeps the host and port`() {
        assertEquals(
            "https://plusvibeapi.ru",
            PlusVibeMediaTtsClient.mediaRoot("https://plusvibeapi.ru/v1"),
        )
        assertEquals(
            "https://plusvibeapi.ru",
            PlusVibeMediaTtsClient.mediaRoot("https://plusvibeapi.ru"),
        )
        assertEquals(
            "https://plusvibeapi.ru/proxy",
            PlusVibeMediaTtsClient.mediaRoot("https://plusvibeapi.ru/proxy/v1"),
        )
        assertNull("кривая база — не URL", PlusVibeMediaTtsClient.mediaRoot("не url"))
    }

    @Test
    fun `prompt is trimmed and cut on a word boundary`() {
        assertEquals("привет", PlusVibeMediaTtsClient.preparePrompt("  привет  "))
        assertNull(PlusVibeMediaTtsClient.preparePrompt("   "))

        val long = "слово ".repeat(2_000)
        val cut = PlusVibeMediaTtsClient.preparePrompt(long, limit = 100)!!
        assertTrue("лимит обязан соблюдаться", cut.length <= 100)
        assertTrue("обрезка — это префикс исходника", long.startsWith(cut))
        assertEquals(
            "обрезка не должна рвать слово: следующий символ исходника — пробел",
            ' ',
            long[cut.length],
        )
    }

    @Test
    fun `engine opt exists only for elevenlabs and price is locale independent`() {
        assertEquals("turbo", PlusVibeMediaTtsClient.engineFor("elevenlabs-v4-tts"))
        assertNull(PlusVibeMediaTtsClient.engineFor("gpt-4o-mini-tts"))
        assertEquals("30.00", PlusVibeMediaTtsClient.priceJson(30.0))
        assertEquals("0.50", PlusVibeMediaTtsClient.priceJson(0.5))
    }

    /**
     * Лимит длины — свойство МОДЕЛИ (замерено: 4096 / 5000 / 15000), а не одно число на всех.
     * Резать текст ElevenLabs по лимиту gpt-4o-mini значит терять последнюю пятую часть
     * законной реплики — и человек этого не слышит, а не получает.
     */
    @Test
    fun `prompt limit follows the selected model and elevenlabs keeps its full text`() {
        assertEquals(5000, PlusVibeMediaTtsClient.promptLimitFor("elevenlabs-v4-tts"))
        assertEquals(5000, PlusVibeMediaTtsClient.promptLimitFor("espeech-tts"))
        assertEquals(4096, PlusVibeMediaTtsClient.promptLimitFor("gpt-4o-mini-tts"))
        assertEquals(15000, PlusVibeMediaTtsClient.promptLimitFor("grok-tts"))
        assertEquals(
            "незнакомая модель — по самому строгому лимиту",
            4096,
            PlusVibeMediaTtsClient.promptLimitFor("some-new-tts"),
        )

        // 4600 знаков: законно для ElevenLabs (5000) и слишком много для gpt-4o-mini (4096).
        val text = "слово ".repeat(800).trim()
        assertEquals(4799, text.length)

        server.enqueue(json(202, """{"ok":true,"jobId":"job-12","status":"processing"}"""))
        server.enqueue(json(200, """{"status":"success","resultUrls":[]}"""))
        runBlocking { client().synthesize(text, "Alice", model = "elevenlabs-v4-tts") }
        val kept = promptOf(server.takeRequest().body.readUtf8())
        server.takeRequest() // GET задачи: иначе он всплывёт в следующем takeRequest
        assertEquals("ElevenLabs обязан получить текст целиком", text, kept)

        server.enqueue(json(202, """{"ok":true,"jobId":"job-13","status":"processing"}"""))
        server.enqueue(json(200, """{"status":"success","resultUrls":[]}"""))
        runBlocking { client().synthesize(text, "onyx", model = "gpt-4o-mini-tts") }
        val cut = promptOf(server.takeRequest().body.readUtf8())
        assertTrue("лимит gpt-4o-mini — 4096: ${cut.length}", cut.length <= 4096)
        assertTrue("обрезка — префикс исходника", text.startsWith(cut))
    }

    @Test
    fun `a non elevenlabs model travels without the engine opt`() {
        server.enqueue(json(202, """{"ok":true,"jobId":"job-14","status":"processing"}"""))
        server.enqueue(json(200, """{"status":"success","resultUrls":[]}"""))

        runBlocking { client().synthesize("фраза", "onyx", model = "gpt-4o-mini-tts") }

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"model\":\"gpt-4o-mini-tts\""))
        assertTrue(body, body.contains("\"voice\":\"onyx\""))
        assertFalse("engine есть только у ElevenLabs", body.contains("\"engine\""))
        assertTrue("предохранитель цены уходит всегда", body.contains("\"max_price_rub\":30.00"))
    }

    // --- голос из библиотеки провайдера (`opts.voice_id`) ---------------------------------
    //
    // Русские голоса ElevenLabs (Larisa, Kate) НЕ входят в перечисление 21 имени, поэтому
    // единственный способ их выбрать — отдельное поле `voice_id`. Живой запрос 18.11.2026
    // подтвердил: провайдер принимает его и эхом возвращает в `effectiveOpts`, но задача
    // может не завершиться вовсе — поэтому ограничение ожидания живёт в профиле
    // ([ServerTtsVoiceProfile.waitBudgetMs]), а не здесь.

    @Test
    fun `library voice is sent as voice id and never as the enum name`() {
        server.enqueue(json(202, """{"ok":true,"jobId":"job-20","status":"processing"}"""))
        server.enqueue(json(200, """{"status":"success","resultUrls":[]}"""))

        runBlocking {
            client().synthesize(
                text = "фраза",
                voice = "Larisa",
                model = "elevenlabs-v4-tts",
                voiceId = "AB9XsbSA4eLG12t2myjN",
            )
        }

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"voice_id\":\"AB9XsbSA4eLG12t2myjN\""))
        // Оба поля одновременно ломают выбор: живой запрос с ними висел в `processing`.
        assertFalse("имя голоса не должно уезжать вместе с voice_id", body.contains("\"voice\":"))
        // Остальные опты не меняются: язык, формат и движок те же.
        assertTrue(body, body.contains("\"language_code\":\"ru\""))
        assertTrue(body, body.contains("\"output_format\":\"mp3\""))
        assertTrue(body, body.contains("\"engine\":\"turbo\""))
    }

    @Test
    fun `blank voice id falls back to the named voice instead of an empty opt`() {
        server.enqueue(json(202, """{"ok":true,"jobId":"job-21","status":"processing"}"""))
        server.enqueue(json(200, """{"status":"success","resultUrls":[]}"""))

        runBlocking {
            client().synthesize("фраза", "Alice", model = "elevenlabs-v4-tts", voiceId = "   ")
        }

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"voice\":\"Alice\""))
        assertFalse("пустой voice_id — это отсутствие выбора, а не пустая строка", body.contains("voice_id"))
    }

    /**
     * Шаг опроса: сразу после `POST` — частый, дальше — обычный.
     *
     * Живой замер: задача почти всегда готова внутри `POST`, а редкий «дозревший сразу после»
     * случай иначе ждал бы полный шаг 1,2 с — это слышимая пауза в речи, которую клиент
     * способен убрать.
     */
    @Test
    fun `first poll is immediate then the step is short before it settles down`() {
        val steps = mutableListOf<Long>()
        var clock = 0L
        var requestsAtFirstSleep = -1
        server.enqueue(json(202, """{"ok":true,"jobId":"job-22","status":"processing"}"""))
        repeat(5) { server.enqueue(json(200, """{"status":"processing"}""")) }
        server.enqueue(json(200, """{"status":"success","resultUrls":[]}"""))

        val client = PlusVibeMediaTtsClient(
            baseUrlProvider = { server.url("/").toString() },
            apiKeyProvider = { "sk-test" },
            httpClient = OkHttpClient(),
            pollIntervalMs = 1_200L,
            earlyPollIntervalMs = 250L,
            sleeper = { millis ->
                if (requestsAtFirstSleep < 0) requestsAtFirstSleep = server.requestCount
                steps += millis
                clock += millis
            },
            nowMs = { clock },
        )

        runBlocking { client.synthesize("фраза", "Alice") }

        // Первый `GET` уходит БЕЗ ожидания: к моменту первого `sleep` в сети уже два запроса
        // (`POST` + `GET`). Иначе готовый результат ждал бы полный шаг.
        assertEquals("первый опрос идёт сразу после POST", 2, requestsAtFirstSleep)
        // Четыре коротких шага укладываются в окно 1 с, затем шаг становится обычным.
        assertEquals(
            "ранние шаги короткие, после окна — обычный шаг",
            listOf(250L, 250L, 250L, 250L, 1_200L),
            steps,
        )
    }

    @Test
    fun `result url candidates prefer audio and payload sniffing rejects json and html`() {
        assertEquals(
            "аудио раньше неизвестного, документы — последними",
            listOf("b.mp3", "c", "a.json"),
            PlusVibeMediaTtsClient.audioCandidates(listOf("a.json", "c", "b.mp3")),
        )
        assertEquals(
            "подписанные ссылки без расширения сохраняют порядок провайдера",
            listOf("/api/media/file/x/0?sig=a", "/api/media/file/x/1?sig=b"),
            PlusVibeMediaTtsClient.audioCandidates(
                listOf("/api/media/file/x/0?sig=a", "/api/media/file/x/1?sig=b"),
            ),
        )

        assertTrue(PlusVibeMediaTtsClient.looksLikeAudioPayload("audio/mpeg", byteArrayOf(-1, -5)))
        assertTrue(PlusVibeMediaTtsClient.looksLikeAudioPayload(null, "RIFF".toByteArray()))
        assertTrue(
            "octet-stream от CDN — нормальный Content-Type для mp3",
            PlusVibeMediaTtsClient.looksLikeAudioPayload("application/octet-stream", byteArrayOf(-1, -5)),
        )
        assertFalse(PlusVibeMediaTtsClient.looksLikeAudioPayload("application/json", """{"words":[]}""".toByteArray()))
        assertFalse(
            "JSON с ведущими пробелами тоже не аудио",
            PlusVibeMediaTtsClient.looksLikeAudioPayload(null, "  \n{".toByteArray()),
        )
        assertFalse(PlusVibeMediaTtsClient.looksLikeAudioPayload("text/html", "<html>".toByteArray()))
        assertFalse("пустое тело — не аудио", PlusVibeMediaTtsClient.looksLikeAudioPayload(null, ByteArray(0)))
    }

    @Test
    fun `only https and loopback bases may carry the key`() {
        assertTrue(PlusVibeMediaTtsClient.isAllowedBase("https://plusvibeapi.ru"))
        assertTrue(PlusVibeMediaTtsClient.isAllowedBase("https://plusvibeapi.ru/proxy"))
        assertFalse(
            "ключ по открытому http — утечка",
            PlusVibeMediaTtsClient.isAllowedBase("http://plusvibeapi.ru"),
        )
        assertTrue(
            "петля — это MockWebServer и локальный прокси на самом устройстве",
            PlusVibeMediaTtsClient.isAllowedBase("http://127.0.0.1:8080"),
        )
        assertTrue(PlusVibeMediaTtsClient.isAllowedBase("http://localhost:8080"))
        assertFalse("не URL", PlusVibeMediaTtsClient.isAllowedBase("не url"))
    }

    @Test
    fun `client is a paid tts port so the engine can swap routes`() {
        val port: PaidTtsClient = client()
        assertTrue(port is PlusVibeMediaTtsClient)
    }
}
