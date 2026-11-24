package ru.mars.network.local

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Сквозная проверка: параметры хода доезжают до ТЕЛА запроса, а не только до типа.
 *
 * Зачем отдельно от `LocalTurnSettingsTest`: тот проверяет структуру (merge, JSON,
 * дефолты), но не отвечает на единственный вопрос, который важен человеку, — «уйдёт ли
 * мой top_p провайдеру». Ошибка в теле запроса не ловится ни тестом структуры, ни
 * компилятором: поле просто не попадёт в JSON, и всё «сработает».
 *
 * Три случая, и каждый — отдельное обещание:
 *  1. `null` — прежнее поведение байт в байт (старые профили не должны измениться);
 *  2. заданные значения уходят ровно теми именами, что ждёт OpenAI-совместимый API;
 *  3. оставленное провайдеру — НЕ уходит, иначе наш дефолт молча подменяет его.
 */
class LocalTurnSettingsBodyTest {

    @Test
    fun nullSettingsKeepLegacyBodyByteForByte() {
        val body = send(settings = null, profile = profile(temperature = 0.7, maxTokens = 512))

        assertTrue("temperature профиля обязана остаться", body.contains("\"temperature\":0.7"))
        assertTrue("max_tokens профиля обязан остаться", body.contains("\"max_tokens\":512"))
        assertFalse("top_p без настроек уходить не должен", body.contains("top_p"))
        assertFalse("stop без настроек уходить не должен", body.contains("\"stop\""))
    }

    @Test
    fun configuredValuesTravelUnderWireNames() {
        val body = send(
            settings = LocalTurnSettings(
                temperature = 0.35,
                topP = 0.9,
                topK = 40,
                minP = 0.05,
                presencePenalty = 0.4,
                frequencyPenalty = 0.2,
                seed = 12345,
                maxTokens = 1024,
                stop = listOf("\n\n", "###"),
            ),
            profile = profile(),
        )

        assertTrue("temperature", body.contains("\"temperature\":0.35"))
        assertTrue("top_p", body.contains("\"top_p\":0.9"))
        assertTrue("top_k целым", body.contains("\"top_k\":40"))
        assertTrue("min_p", body.contains("\"min_p\":0.05"))
        assertTrue("presence_penalty", body.contains("\"presence_penalty\":0.4"))
        assertTrue("frequency_penalty", body.contains("\"frequency_penalty\":0.2"))
        assertTrue("seed без дроби", body.contains("\"seed\":12345"))
        assertTrue("max_tokens целым", body.contains("\"max_tokens\":1024"))
        assertTrue("stop массивом строк", body.contains("\"stop\":[\"\\n\\n\",\"###\"]"))
    }

    @Test
    fun settingsOverrideProfileValues() {
        val body = send(
            settings = LocalTurnSettings(temperature = 0.1),
            profile = profile(temperature = 1.6, maxTokens = 4096),
        )

        assertTrue("температура хода вытесняет профиль", body.contains("\"temperature\":0.1"))
        assertFalse("старая температура не должна остаться", body.contains("\"temperature\":1.6"))
    }

    @Test
    fun valuesLeftToProviderAreNotSent() {
        // top_k/min_p/repetition_penalty/seed не имеют дефолта приложения: пока человек их
        // не задал, поля не должно быть вовсе — иначе провайдер получит наше молчаливое
        // решение вместо своего.
        val body = send(settings = LocalTurnSettings(temperature = 0.7), profile = profile())

        assertFalse("top_k не задан — не отправляем", body.contains("top_k"))
        assertFalse("min_p не задан — не отправляем", body.contains("min_p"))
        assertFalse("repetition_penalty не задан — не отправляем", body.contains("repetition_penalty"))
        assertFalse("seed не задан — не отправляем", body.contains("seed"))
        assertFalse("пустой stop не отправляем", body.contains("\"stop\""))
    }

    // --- helpers ------------------------------------------------------------

    private fun profile(
        temperature: Double? = null,
        maxTokens: Int? = null,
    ) = LocalProviderProfile(
        id = "p1",
        title = "Тест",
        baseUrl = "https://example.invalid/v1",
        model = "test-model",
        temperature = temperature,
        maxTokens = maxTokens,
    )

    /** Отправить один шаг на поддельный сервер и вернуть тело последнего запроса. */
    private fun send(settings: LocalTurnSettings?, profile: LocalProviderProfile): String {
        val client = LocalClientFixture.clientOrNull()
        assumeTrue(LocalClientFixture.MISSING_MESSAGE, client != null)

        return MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ок\"}}]}\n\ndata: [DONE]\n\n"),
            )
            val base = server.url("/v1").toString().trimEnd('/')
            runBlocking {
                withTimeout(30_000) {
                    client!!.stream(
                        profile = profile.copy(baseUrl = base),
                        apiKey = "test-key",
                        messages = listOf(LocalMessage(id = "u1", role = LocalRole.USER, text = "привет")),
                        tools = emptyList(),
                        settings = settings,
                    ).toList()
                }
            }
            val recorded = server.takeRequest()
            assertNotNull("запрос обязан дойти до сервера", recorded)
            recorded.body.readUtf8()
        }
    }
}
