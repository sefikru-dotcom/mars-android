package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.OpenAiSpeechTtsClient
import ru.mars.network.repository.ServerTtsContract
import ru.mars.network.repository.ServerTtsPrefetchKeys
import ru.mars.network.repository.ServerTtsRepository
import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsRetryPolicy
import ru.mars.network.repository.ServerTtsSpeechRequest
import ru.mars.network.repository.ServerTtsVoiceProfile
import ru.mars.network.repository.ServerTtsVoices

/**
 * Голосовой профиль `api/tts`, `Retry-After` и ограниченный повтор.
 *
 * Смысл набора: Android раньше слал `{"text":…}` без `engine`/`voice` и получал
 * `piper/ru_RU-dmitri-medium` вместо голоса веба. Здесь фиксируется, что (а) пара
 * engine+voice уходит в тело, (б) `429` от лимитера обрабатывается ОДНИМ ограниченным
 * повтором, а не бесконечными ретраями и не потерей фразы.
 */
class ServerTtsVoiceContractTest {

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    private fun audioResponse() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "audio/wav")
        .setBody(okio.Buffer().write(byteArrayOf(1, 2, 3)))

    // --- профили --------------------------------------------------------------------------

    @Test
    fun `plusvibe alice is the primary voice and svetlana is the first free fallback`() {
        assertEquals(
            listOf(
                "plusvibe-alice",
                "natural-svetlana",
                "natural-dmitry",
                "natural-ava",
                "plusvibe-onyx",
                "plusvibe-larisa",
                "plusvibe-kate",
                "local-irina",
            ),
            ServerTtsVoices.VOICE_DIALOG.map { it.id },
        )
        // Основной голос — платный PlusVibe: он идёт первым в переборе и он же дефолт.
        val primary = ServerTtsVoices.VOICE_DIALOG.first()
        assertTrue("основной голос обязан быть помечен natural", primary.natural)
        assertEquals("plusvibe", primary.engine)
        assertEquals("Alice", primary.voice)
        assertEquals("elevenlabs-v4-tts", primary.model)
        assertTrue("платный голос требует сети", primary.needsNetwork)
        assertEquals(primary, ServerTtsVoices.DEFAULT_VOICE)

        // Светлана остаётся первым БЕСПЛАТНЫМ голосом: если ключа провайдера нет, звучит она.
        val svetlana = ServerTtsVoices.NATURAL_SVETLANA
        assertEquals("edge", svetlana.engine)
        assertEquals("ru-RU-SvetlanaNeural", svetlana.voice)
        assertEquals("+8%", svetlana.rate)
        assertEquals("+0Hz", svetlana.pitch)
        assertEquals(1, ServerTtsVoices.VOICE_DIALOG.indexOf(svetlana))
    }

    @Test
    fun `plusvibe onyx is the cheap paid profile and sits before the local reserve`() {
        val onyx = ServerTtsVoices.byId("plusvibe-onyx")!!

        // engine = "openai" — исторический маршрут платного канала, а не поле запроса /api/tts.
        assertEquals("openai", onyx.engine)
        assertEquals("onyx", onyx.voice)
        // Модель живёт в профиле: голос `onyx` существует только внутри gpt-4o-mini-tts.
        assertEquals("gpt-4o-mini-tts", onyx.model)
        assertEquals("+5%", onyx.rate)
        assertEquals("+0Hz", onyx.pitch)
        assertTrue("платный голос — нейро-голос, а не резерв", onyx.natural)
        assertTrue(onyx.needsNetwork)
        assertFalse(onyx.isLocal)
        // Перед локальным резервом и после основного PlusVibe и трёх edge-голосов.
        assertEquals(4, ServerTtsVoices.VOICE_DIALOG.indexOf(onyx))
        assertEquals(ServerTtsVoices.LOCAL_IRINA, ServerTtsVoices.VOICE_DIALOG.last())
    }

    @Test
    fun `natural ava is the third distinct web voice`() {
        val ava = ServerTtsVoices.byId("natural-ava")!!

        assertEquals("edge", ava.engine)
        assertEquals("en-US-AvaMultilingualNeural", ava.voice)
        assertEquals("+5%", ava.rate)
        assertTrue(ava.natural)
        assertTrue(ava.needsNetwork)
        // Семь сетевых профилей (четыре платных PlusVibe + три edge), восьмой — локальный резерв.
        assertEquals(7, ServerTtsVoices.VOICE_DIALOG.count { it.needsNetwork })
        assertEquals(
            ServerTtsVoices.VOICE_DIALOG.map { it.voice }.distinct().size,
            ServerTtsVoices.VOICE_DIALOG.size,
        )
    }

    @Test
    fun `natural dmitry is the second edge voice and still needs the network`() {
        val dmitry = ServerTtsVoices.byId("natural-dmitry")!!

        assertEquals("edge", dmitry.engine)
        assertEquals("ru-RU-DmitryNeural", dmitry.voice)
        assertEquals("+5%", dmitry.rate)
        assertTrue("Dmitry — голос веба, а не резерв", dmitry.natural)
        assertTrue("edge требует сети", dmitry.needsNetwork)
        assertFalse(dmitry.isLocal)
        // Третий в списке: перед ним основной PlusVibe и Светлана.
        assertEquals(dmitry, ServerTtsVoices.VOICE_DIALOG[2])
    }

    @Test
    fun `local irina is a fallback and never replaces the natural voice by default`() {
        val fallback = ServerTtsVoices.byId("local-irina")!!
        assertFalse(fallback.natural)
        assertTrue(fallback.isLocal)
        assertEquals("piper", fallback.engine)
        assertEquals("ru_RU-irina-medium", fallback.voice)
        // Резерв не должен стоять первым ни при каких условиях: иначе он станет «навязанным».
        assertFalse(ServerTtsVoices.VOICE_DIALOG.first() === fallback)
    }

    @Test
    fun `unknown profile id is null not a crash`() {
        assertNull(ServerTtsVoices.byId("ru_RU-dmitri-medium"))
        assertNull(ServerTtsVoices.byId(""))
        assertNull(ServerTtsVoices.byId(null))
    }

    // --- русские голоса из библиотеки провайдера ------------------------------------------
    //
    // Требование: в локальном чате должны быть РУССКИЕ тембры, а не британская `Alice`.
    // Русские голоса у ElevenLabs живут только в библиотеке (`opts.voice_id`), поэтому
    // профиль обязан нести id, а не имя из перечисления 21 голоса. Звучание на слух НЕ
    // проверялось: здесь фиксируется контракт, а не качество.

    @Test
    fun `russian library voices travel by voice id and never by name`() {
        val larisa = ServerTtsVoices.byId("plusvibe-larisa")!!
        val kate = ServerTtsVoices.byId("plusvibe-kate")!!

        assertEquals(ServerTtsVoices.LIBRARY_VOICE_LARISA, larisa.voiceId)
        assertEquals("AB9XsbSA4eLG12t2myjN", larisa.voiceId)
        assertEquals(ServerTtsVoices.LIBRARY_VOICE_KATE, kate.voiceId)
        assertEquals("7G0NvIkWRnU0Dqjgz13p", kate.voiceId)
        // Имя — только подпись: в перечислении 21 голоса таких имён нет.
        assertEquals("Larisa", larisa.voice)
        assertEquals("Kate", kate.voice)
        // Модель и маршрут те же, что у основного платного голоса: media-контракт, не /api/tts.
        assertEquals("elevenlabs-v4-tts", larisa.model)
        assertEquals("plusvibe", larisa.engine)
        assertEquals("plusvibe", kate.engine)
        assertTrue(larisa.natural)
        assertTrue(kate.natural)
        // Платные и сетевые: бесплатным резервом они быть не могут.
        assertFalse(larisa.isLocal)
        assertTrue(larisa.needsNetwork)
    }

    @Test
    fun `library voice carries a wait budget so a hung job does not silence the phrase`() {
        val larisa = ServerTtsVoices.PLUSVIBE_LARISA

        // Живой дефект канала: задача с `voice_id` не завершается вовсе. Бюджет обязан быть
        // и обязан быть заметно меньше транспортного потолка клиента, иначе реплика молчит.
        assertEquals(ServerTtsVoices.LIBRARY_VOICE_WAIT_BUDGET_MS, larisa.waitBudgetMs)
        assertTrue(
            "бюджет обязан быть короче транспортного потолка (90 с), иначе он бесполезен",
            larisa.waitBudgetMs!! < 90_000L,
        )
        // Именованные голоса бюджета НЕ получают: их медленный, но живой ответ обрывать
        // нельзя — это была бы двойная оплата за ту же фразу.
        assertNull(ServerTtsVoices.PLUSVIBE_ALICE.waitBudgetMs)
        assertNull(ServerTtsVoices.PLUSVIBE_ONYX.waitBudgetMs)
        // Библиотечный голос никогда не становится дефолтом: он платный и пока нестабилен.
        assertFalse(ServerTtsVoices.DEFAULT_VOICE === larisa)
        assertEquals(ServerTtsVoices.PLUSVIBE_ALICE, ServerTtsVoices.DEFAULT_VOICE)
    }

    @Test
    fun `library profile log tag prints the id that actually travels`() {
        // В логе должно быть видно то, что уехало в запрос: имени `Larisa` в контракте нет.
        assertEquals(
            "plusvibe-larisa(plusvibe/AB9XsbSA4eLG12t2myjN)",
            ServerTtsVoices.PLUSVIBE_LARISA.logTag,
        )
        assertEquals(
            "plusvibe-alice(plusvibe/Alice)",
            ServerTtsVoices.PLUSVIBE_ALICE.logTag,
        )
    }

    @Test
    fun `body carries engine and voice together`() {
        val request = ServerTtsSpeechRequest.of("привет", ServerTtsVoices.NATURAL_SVETLANA)
        val body = ServerTtsContract.buildBody(request)

        assertEquals(
            "{\"text\":\"привет\",\"engine\":\"edge\",\"voice\":\"ru-RU-SvetlanaNeural\",\"rate\":\"+8%\",\"pitch\":\"+0Hz\"}",
            body,
        )
    }

    @Test
    fun `null profile keeps the old body so the server default is unchanged`() {
        assertEquals("{\"text\":\"привет\"}", ServerTtsContract.buildBody("привет"))
        assertEquals(
            "{\"text\":\"привет\"}",
            ServerTtsContract.buildBody(ServerTtsSpeechRequest.of("привет", null)),
        )
    }

    @Test
    fun `blank engine or voice never reaches the body`() {
        val body = ServerTtsContract.buildBody(
            ServerTtsSpeechRequest(text = "текст", engine = "  ", voice = "   "),
        )
        assertEquals("{\"text\":\"текст\"}", body)
    }

    @Test
    fun `profile without rate omits the rate field`() {
        val body = ServerTtsContract.buildBody(ServerTtsSpeechRequest.of("текст", ServerTtsVoices.LOCAL_IRINA))
        assertEquals("{\"text\":\"текст\",\"engine\":\"piper\",\"voice\":\"ru_RU-irina-medium\"}", body)
    }

    @Test
    fun `repository sends the explicit voice pair over the wire`() {
        MockWebServer().use { server ->
            server.enqueue(audioResponse())
            val result = runBlocking {
                ServerTtsRepository().synthesize(
                    server.url("/").toString(),
                    credential(),
                    ServerTtsSpeechRequest.of("привет", ServerTtsVoices.NATURAL_SVETLANA),
                )
            }

            assertTrue(result is ServerTtsResult.Audio)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"engine\":\"edge\""))
            assertTrue(body, body.contains("\"voice\":\"ru-RU-SvetlanaNeural\""))
        }
    }

    @Test
    fun `repository still accepts plain text with engine and voice arguments`() {
        MockWebServer().use { server ->
            server.enqueue(audioResponse())
            runBlocking {
                ServerTtsRepository().synthesize(
                    server.url("/").toString(), credential(), "текст",
                    engine = "piper", voice = "ru_RU-irina-medium",
                )
            }
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"engine\":\"piper\""))
            assertTrue(body, body.contains("\"voice\":\"ru_RU-irina-medium\""))
        }
    }

    // --- Retry-After ----------------------------------------------------------------------

    @Test
    fun `retry after seconds is parsed to millis`() {
        assertEquals(2_000L, ServerTtsRetryPolicy.parseRetryAfterMs("2", nowMs = 0L))
        assertEquals(0L, ServerTtsRetryPolicy.parseRetryAfterMs("0", nowMs = 0L))
        assertEquals(1_000L, ServerTtsRetryPolicy.parseRetryAfterMs(" 1 ", nowMs = 0L))
        assertEquals(120_000L, ServerTtsRetryPolicy.parseRetryAfterMs("120", nowMs = 0L))
    }

    @Test
    fun `retry after http date is parsed against the injected clock`() {
        val now = 1_700_000_000_000L
        val header = "Tue, 14 Nov 2023 22:13:25 GMT" // = 1_700_000_005_000 ms
        val parsed = ServerTtsRetryPolicy.parseRetryAfterMs(header, nowMs = now)
        assertEquals(5_000L, parsed)
    }

    @Test
    fun `past retry after date degrades to zero and garbage to null`() {
        val parsed = ServerTtsRetryPolicy.parseRetryAfterMs(
            "Tue, 14 Nov 2023 22:13:25 GMT",
            nowMs = 1_700_000_100_000L,
        )
        assertEquals(0L, parsed)
        assertNull(ServerTtsRetryPolicy.parseRetryAfterMs("потом", 0L))
        assertNull(ServerTtsRetryPolicy.parseRetryAfterMs(null, 0L))
        assertNull(ServerTtsRetryPolicy.parseRetryAfterMs("  ", 0L))
    }

    @Test
    fun `delay is capped so speech never waits for an absurd Retry-After`() {
        val policy = ServerTtsRetryPolicy(maxAttempts = 2, maxDelayMs = 2_500L)
        assertEquals(2_500L, policy.delayBeforeRetryMs(attempt = 1, retryAfterMs = 60_000L))
        assertEquals(900L, policy.delayBeforeRetryMs(attempt = 1, retryAfterMs = 900L))
        // Без заголовка работает бэкофф ≈ окно лимитера.
        assertEquals(
            ServerTtsRetryPolicy.DEFAULT_BACKOFF_MS,
            policy.delayBeforeRetryMs(attempt = 1, retryAfterMs = null),
        )
    }

    @Test
    fun `only limiter and server errors are retryable`() {
        val policy = ServerTtsRetryPolicy()
        assertTrue(policy.isRetryable("rate_limited"))
        assertTrue(policy.isRetryable("server_error"))
        // Терминальные ответы — сигнал сменить голос/токен, а не ждать.
        assertFalse(policy.isRetryable("unauthorized"))
        assertFalse(policy.isRetryable("not_found"))
        assertFalse(policy.isRetryable("invalid_response"))
        assertFalse(policy.isRetryable("transport"))
    }

    // --- повтор на 429 --------------------------------------------------------------------

    @Test
    fun `429 is retried once and the second answer wins`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Retry-After", "0")
                    .setBody("{\"error\":\"rate limit exceeded\"}"),
            )
            server.enqueue(audioResponse())
            val repository = ServerTtsRepository(
                retryPolicy = ServerTtsRetryPolicy(maxAttempts = 2, maxDelayMs = 0L),
            )

            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            assertTrue("после 429 должен пройти повтор", result is ServerTtsResult.Audio)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `retry budget is bounded - third 429 is returned as unavailable`() {
        MockWebServer().use { server ->
            repeat(3) {
                server.enqueue(
                    MockResponse().setResponseCode(429)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"error\":\"rate limit exceeded\"}"),
                )
            }
            val repository = ServerTtsRepository(
                retryPolicy = ServerTtsRetryPolicy(maxAttempts = 2, maxDelayMs = 0L),
            )

            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            val unavailable = result as ServerTtsResult.Unavailable
            assertEquals("rate_limited", unavailable.reason)
            assertEquals(429, unavailable.httpCode)
            // Ровно две попытки: третья не уходит, даже если сервер снова ответил бы 429.
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `unavailable carries the parsed retry after for the queue`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Retry-After", "2")
                    .setBody("{\"error\":\"rate limit exceeded\"}"),
            )
            server.enqueue(audioResponse())
            val repository = ServerTtsRepository(
                retryPolicy = ServerTtsRetryPolicy(maxAttempts = 1, maxDelayMs = 2_500L),
            )

            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            val unavailable = result as ServerTtsResult.Unavailable
            assertEquals(2_000L, unavailable.retryAfterMs)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `terminal 401 is not retried`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":\"unauthorized\"}"),
            )
            server.enqueue(audioResponse())
            val repository = ServerTtsRepository(
                retryPolicy = ServerTtsRetryPolicy(maxAttempts = 3, maxDelayMs = 0L),
            )

            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            assertEquals("unauthorized", (result as ServerTtsResult.Unavailable).reason)
            assertEquals("401 не повторяется: это токен, а не лимитер", 1, server.requestCount)
        }
    }

    @Test
    fun `unknown voice 400 is not retried`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":\"unknown voice\"}"),
            )
            server.enqueue(audioResponse())
            val repository = ServerTtsRepository(
                retryPolicy = ServerTtsRetryPolicy(maxAttempts = 2, maxDelayMs = 0L),
            )

            val result = runBlocking {
                repository.synthesize(
                    server.url("/").toString(), credential(),
                    ServerTtsSpeechRequest.of("текст", ServerTtsVoices.LOCAL_IRINA),
                )
            }

            assertEquals("http_400", (result as ServerTtsResult.Unavailable).reason)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `profile id and voice name are the only strings in the log tag`() {
        val profile: ServerTtsVoiceProfile = ServerTtsVoices.NATURAL_SVETLANA
        assertEquals("natural-svetlana(edge/ru-RU-SvetlanaNeural)", profile.logTag)
        assertFalse(profile.logTag.contains("token"))
    }

    // --- Кэш предсинтеза: голос обязан входить в ключ ---------------------------------------
    //
    // Дефект: кэш индексировался ОДНИМ текстом, поэтому смена голоса посреди ответа оставляла
    // остаток фраз прежним голосом. Проверяется чистая функция ключа — то есть ровно то, от
    // чего зависит «фраза другого голоса не будет отдана как своя».

    @Test
    fun `prefetch key is bound to the voice that synthesized the phrase`() {
        val dmitry = ServerTtsPrefetchKeys.of(ServerTtsVoices.NATURAL_DMITRY.id, "фраза")
        val svetlana = ServerTtsPrefetchKeys.of(ServerTtsVoices.NATURAL_SVETLANA.id, "фраза")

        assertNotEquals("фраза одного голоса не может быть отдана другому", dmitry, svetlana)
        // Тот же голос и тот же текст — тот же ключ: повтор фразы (эхо, «Проверить») берёт
        // готовое аудио, а не платит за второй синтез.
        assertEquals(dmitry, ServerTtsPrefetchKeys.of(ServerTtsVoices.NATURAL_DMITRY.id, "фраза"))
    }

    @Test
    fun `prefetch key does not collide when a voice id is glued to a text`() {
        // Без разделителя `("a", "bc")` и `("ab", "c")` дали бы одну строку "abc".
        assertNotEquals(
            ServerTtsPrefetchKeys.of("a", "bc"),
            ServerTtsPrefetchKeys.of("ab", "c"),
        )
    }

    @Test
    fun `no chosen voice is its own key and not the empty id`() {
        val web = ServerTtsPrefetchKeys.of(null, "текст")

        // «Как в вебе» — это ВЫБОР по умолчанию, а не отсутствие голоса: он не должен
        // совпадать с ключом явно выбранного профиля.
        assertNotEquals(web, ServerTtsPrefetchKeys.of(ServerTtsVoices.PLUSVIBE_ALICE.id, "текст"))
        // Пустой id — то же самое, что «не выбрано»: два разных написания одного состояния.
        assertEquals(web, ServerTtsPrefetchKeys.of("   ", "текст"))
    }

    // --- Платный облачный синтез: OpenAI-совместимый /v1/audio/speech ----------------------
    //
    // Контракт провайдера (проверен живым запросом к PlusVibe): модель `gpt-4o-mini-tts`,
    // тело `model`/`input`/`voice`/`response_format`/`speed`(/`instructions`), ответ — mp3
    // байтами. Без ключа запрос НЕ отправляется: иначе это 401 и лишняя пауза в речи.

    @Test
    fun `openai speech posts the contract body to audio speech with the provider key`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setBody(okio.Buffer().write(byteArrayOf(1, 2, 3, 4))),
            )
            val client = OpenAiSpeechTtsClient(
                baseUrlProvider = { server.url("/v1").toString() },
                apiKeyProvider = { "sk-test" },
            )

            val result = runBlocking { client.synthesize("привет, Марс", "onyx", speed = 1.2f) }

            assertTrue("200 с mp3 обязан дать аудио", result is ServerTtsResult.Audio)
            assertEquals("audio/mpeg", (result as ServerTtsResult.Audio).contentType)
            val request = server.takeRequest()
            assertEquals("/v1/audio/speech", request.path)
            assertEquals("Bearer sk-test", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body, body.contains("\"model\":\"gpt-4o-mini-tts\""))
            assertTrue(body, body.contains("\"input\":\"привет, Марс\""))
            assertTrue(body, body.contains("\"voice\":\"onyx\""))
            assertTrue(body, body.contains("\"response_format\":\"mp3\""))
            assertTrue(body, body.contains("\"speed\":1.2"))
            assertTrue("манера чтения уезжает полем instructions", body.contains("\"instructions\":"))
        }
    }

    @Test
    fun `openai speech without a key never touches the network`() {
        MockWebServer().use { server ->
            val client = OpenAiSpeechTtsClient(
                baseUrlProvider = { server.url("/v1").toString() },
                apiKeyProvider = { "  " },
            )

            val result = runBlocking { client.synthesize("привет", "onyx") }

            val unavailable = result as ServerTtsResult.Unavailable
            assertEquals(OpenAiSpeechTtsClient.REASON_NO_KEY, unavailable.reason)
            // Ключа нет — запрос гарантированно 401, поэтому он не отправляется вовсе.
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `openai speech maps a provider error to unavailable instead of throwing`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":\"The requested speech model is unavailable\"}"),
            )
            val client = OpenAiSpeechTtsClient(
                baseUrlProvider = { server.url("/v1").toString() },
                apiKeyProvider = { "sk-test" },
            )

            val result = runBlocking { client.synthesize("привет", "onyx") }

            val unavailable = result as ServerTtsResult.Unavailable
            assertEquals("http_400", unavailable.reason)
            assertEquals(400, unavailable.httpCode)
        }
    }

    @Test
    fun `openai speech treats a 200 without bytes as unavailable`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mpeg"))
            val client = OpenAiSpeechTtsClient(
                baseUrlProvider = { server.url("/v1").toString() },
                apiKeyProvider = { "sk-test" },
            )

            val result = runBlocking { client.synthesize("привет", "onyx") }

            assertEquals(
                OpenAiSpeechTtsClient.REASON_EMPTY_AUDIO,
                (result as ServerTtsResult.Unavailable).reason,
            )
        }
    }

    @Test
    fun `openai speech clamps speed and drops empty instructions`() {
        // Провайдер принимает 0.25..4.0: 9 и 0.01 — это 400 invalid speed, а причина отказа
        // стала бы неотличима от зависания апстрима.
        val fast = OpenAiSpeechTtsClient.buildBody("т", "onyx", speed = 9f, instructions = null)
        assertTrue(fast, fast.contains("\"speed\":4.0"))
        assertFalse("пустая манера чтения не уходит полем", fast.contains("instructions"))

        val slow = OpenAiSpeechTtsClient.buildBody("т", "onyx", speed = 0.01f)
        assertTrue(slow, slow.contains("\"speed\":0.25"))

        val nan = OpenAiSpeechTtsClient.buildBody("т", "onyx", speed = Float.NaN)
        assertTrue(nan, nan.contains("\"speed\":1.0"))
    }
}
