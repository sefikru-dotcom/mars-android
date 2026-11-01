package ru.mars.network.local

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Сквозная проверка B1 на настоящем транспорте: смена конфигурации посреди потока.
 *
 * Почему сквозная, а не только на метке. Проверка «метка устарела» сама по себе ничего не
 * доказывает: важно, что транспорт сверяет её ПЕРЕД КАЖДЫМ эмитом и не отправляет
 * `LocalChatEvent.Error` за то, что человек сам сменил адрес. Ошибка здесь не видна ни
 * компилятору, ни тесту метки — только наблюдаемому потоку событий.
 *
 * Сценарий «посреди потока» сделан детерминированным: поддельный провайдер отдаёт первый
 * кадр и ЖДЁТ, пока тест не сменит метку, и только потом дописывает остаток. Никаких
 * `sleep` и надежд на планировщик — ожидание на защёлке, поэтому тест не «мигает».
 */
class LocalProviderOvertakeStreamTest {

    @Test
    fun completeStreamWithoutOvertakeEmitsEveryEvent() {
        val client = OkHttpLocalChatClient()
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(SSE_TWO_DELTAS),
            )
            val events = collect(client, profile(server.url("/v1").toString().trimEnd('/')))

            val deltas = events.filterIsInstance<LocalChatEvent.Delta>().map { it.text }
            assertEquals("без смены метки поток обязан дойти целиком", listOf("раз", "два"), deltas)

            val finished = events.filterIsInstance<LocalChatEvent.AssistantFinished>().single()
            assertEquals("раздва", finished.fullText)
            assertEquals("finish_reason обязан доехать", "stop", finished.finishReason)
            assertTrue("ошибок быть не должно", events.none { it is LocalChatEvent.Error })
        }
    }

    @Test
    fun flowCreatedBeforeConfigChangeStaysSilent() {
        // Fail-closed: шаг начат по СТАРОМУ профилю, а настройки сменили до подписки.
        // Такой шаг не имеет права ничего отдать — ни текста, ни ошибки.
        val client = OkHttpLocalChatClient()
        val flow = client.stream(brokenProfile(), "test-key", messages())
        client.providerSeq.bump()

        val events = runBlocking { withTimeout(TIMEOUT_MS) { flow.toList() } }

        assertTrue("устаревший шаг обязан молчать", events.isEmpty())
    }

    @Test
    fun invalidProfileStillReportsErrorWhileConfigIsCurrent() {
        // Контроль к предыдущему тесту: без смены метки та же ветка обязана дать ошибку.
        // Иначе «молчание» можно было бы получить простым отключением ошибки.
        val client = OkHttpLocalChatClient()
        val flow = client.stream(brokenProfile(), "test-key", messages())

        val events = runBlocking { withTimeout(TIMEOUT_MS) { flow.toList() } }

        val error = events.filterIsInstance<LocalChatEvent.Error>().single()
        assertTrue("причина обязана быть про адрес: ${error.message}", error.message.contains("адрес"))
    }

    @Test(timeout = 120_000)
    fun configChangeMidStreamStopsEmissionWithoutError() {
        val client = OkHttpLocalChatClient()
        val seq = client.providerSeq
        val firstDeltaSeen = CountDownLatch(1)
        val received = Collections.synchronizedList(mutableListOf<LocalChatEvent>())

        ServerSocket(0).use { server ->
            server.soTimeout = TIMEOUT_MS.toInt()
            val serverThread = thread(name = "fake-sse-provider", isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        val out = socket.getOutputStream()
                        // Ответ без Content-Length и с `Connection: close`: тело кончается
                        // на закрытии сокета — ровно то, что делает живой SSE-сервер.
                        out.write((HEADERS + FIRST_FRAME).toByteArray())
                        out.flush()
                        firstDeltaSeen.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        out.write(REST_FRAMES.toByteArray())
                        out.flush()
                    }
                } catch (_: Exception) {
                    // Клиент закрыл соединение после смены метки — это и есть проверяемое поведение.
                }
            }

            try {
                runBlocking {
                    withTimeout(TIMEOUT_MS) {
                        client.stream(
                            profile = profile("http://127.0.0.1:${server.localPort}/v1"),
                            apiKey = "test-key",
                            messages = messages(),
                        ).collect { event ->
                            received += event
                            if (event is LocalChatEvent.Delta) {
                                // Человек выбрал другую модель, пока ответ печатался.
                                seq.bump()
                                firstDeltaSeen.countDown()
                            }
                        }
                    }
                }
            } finally {
                firstDeltaSeen.countDown()
                serverThread.join(TIMEOUT_MS)
            }
        }

        assertEquals("после смены метки событий быть не должно", 1, received.size)
        assertEquals(LocalChatEvent.Delta("раз"), received.first())
        assertTrue(
            "смена адреса — не сбой: ошибку показывать нельзя",
            received.none { it is LocalChatEvent.Error },
        )
    }

    // --- helpers ------------------------------------------------------------

    private fun collect(client: LocalChatClient, profile: LocalProviderProfile): List<LocalChatEvent> =
        runBlocking { withTimeout(TIMEOUT_MS) { client.stream(profile, "test-key", messages()).toList() } }

    private fun profile(baseUrl: String) = LocalProviderProfile(
        id = "p1",
        title = "Тест",
        baseUrl = baseUrl,
        model = "test-model",
    )

    /** Профиль без рабочего адреса: транспорт обязан отказать ДО сети. */
    private fun brokenProfile() = profile("")

    private fun messages() = listOf(LocalMessage(id = "u1", role = LocalRole.USER, text = "привет"))

    private companion object {
        const val TIMEOUT_MS = 20_000L

        const val HEADERS =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Connection: close\r\n" +
                "\r\n"

        const val FIRST_FRAME =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"раз\"}}]}\n\n"

        const val REST_FRAMES =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"два\"}}]}\n\n" +
                "data: [DONE]\n\n"

        const val SSE_TWO_DELTAS =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"раз\"}}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"два\"}}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
    }
}
