package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ChatStartResponse
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.StreamEventMapper

/**
 * Фактическая модель/провайдер хода в [ChatEvent.StreamStarted] (P0-1, клиентская половина).
 *
 * Wire-контракт кадра начала хода — ответа `POST /api/chat/start` (hermes-webui
 * api/routes.py `_start_chat_stream_for_session` / `_chat_start_response_from_run_start`,
 * сверено read-only по боевому дереву): `{stream_id, session_id, pending_started_at,
 * turn_id, title, effective_model?, effective_model_provider?}`; WebUI (static/messages.js)
 * читает те же ключи `effective_model`/`effective_model_provider`. Ключи аддитивны и
 * условны: их может не быть — тогда поля null («сервер не сообщил»), и это НЕ ошибка.
 * Локальный выбор модели из запроса в StreamStarted подставлять запрещено.
 */
class StreamStartedTurnModelTest {

    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token",
        tokenType = "Bearer",
        deviceId = "device-1",
        scopes = listOf("chat:send"),
        expiresIn = 3600
    )

    private fun enqueueSse(server: MockWebServer) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: token\ndata: {\"text\":\"привет\"}\n\nevent: done\ndata: {}\n\n")
        )
    }

    // ---- маппер: кадр начала хода -------------------------------------------------------------

    @Test
    fun startResponseReadsEffectiveModelAndProviderAdditively() {
        assertEquals(
            ChatStartResponse("st-1", "s-1", "gpt-6.1-sol", "custom:plusvibe-api"),
            StreamEventMapper.startResponse(
                """{"stream_id":"st-1","session_id":"s-1","turn_id":"t-9","title":"Отладка",""" +
                    """"effective_model":"gpt-6.1-sol","effective_model_provider":"custom:plusvibe-api"}"""
            )
        )
    }

    @Test
    fun startResponseToleratesPlainModelAliases() {
        // Аддитивная устойчивость: серверный воркер вправе отдать ключи и без effective_-префикса.
        assertEquals(
            ChatStartResponse("st-1", null, "gemini-3.8-flash", "custom:vertex-gemini"),
            StreamEventMapper.startResponse(
                """{"stream_id":"st-1","model":"gemini-3.8-flash","model_provider":"custom:vertex-gemini"}"""
            )
        )
        // effective_* имеет приоритет перед алиасами.
        val both = StreamEventMapper.startResponse(
            """{"stream_id":"st-1","effective_model":"real","model":"alias","effective_model_provider":"real-p","model_provider":"alias-p"}"""
        )
        assertEquals("real", both?.model)
        assertEquals("real-p", both?.provider)
    }

    @Test
    fun absentOrBlankKeysStayNullBecauseServerDidNotReport() {
        val resp = StreamEventMapper.startResponse(
            """{"stream_id":"st-1","session_id":"s-1","effective_model":"","effective_model_provider":null}"""
        )
        assertEquals(ChatStartResponse("st-1", "s-1", null, null), resp)
        // Без stream_id кадра начала хода нет (прежняя ошибка «Missing stream_id» сохранена).
        assertNull(StreamEventMapper.startResponse("""{"session_id":"s-1"}"""))
        assertNull(StreamEventMapper.startResponse("not json"))
        // Нестроковые значения модели не протаскиваются toString-мусором.
        val structured = StreamEventMapper.startResponse("""{"stream_id":"st-1","effective_model":{"id":"x"}}""")
        assertEquals("st-1", structured?.streamId)
        assertNull(structured?.model)
    }

    // ---- транспорт: StreamStarted несёт серверную правду ---------------------------------------

    @Test
    fun sendMessageStreamStartedCarriesEffectiveModelAndProvider() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"stream_id":"st-1","session_id":"s-1",""" +
                        """"effective_model":"gpt-6.1-sol","effective_model_provider":"custom:plusvibe-api"}"""
                )
            )
            enqueueSse(server)
            val events = ChatRepository()
                .sendMessage(server.url("/").toString(), credential, "привет")
                .toList()
            val started = events.filterIsInstance<ChatEvent.StreamStarted>().single()
            assertEquals("st-1", started.streamId)
            assertEquals("gpt-6.1-sol", started.model)
            assertEquals("custom:plusvibe-api", started.provider)
        }
    }

    @Test
    fun sendMessageWithoutModelKeysStaysNullAndStreamContinues() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1","session_id":"s-1"}"""))
            enqueueSse(server)
            val events = ChatRepository()
                .sendMessage(server.url("/").toString(), credential, "привет")
                .toList()
            val started = events.filterIsInstance<ChatEvent.StreamStarted>().single()
            assertNull(started.model)
            assertNull(started.provider)
            // null — не ошибка: ход стримится штатно.
            assertEquals("привет", events.filterIsInstance<ChatEvent.Chunk>().single().text)
            assertTrue(events.contains(ChatEvent.Done))
        }
    }

    @Test
    fun locallyRequestedModelNeverLeaksIntoStreamStarted() = runBlocking {
        // В запросе локальный выбор есть, в ответе effective-ключей нет:
        // StreamStarted остаётся null — клиент не выдаёт догадку за факт.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1"}"""))
            enqueueSse(server)
            val events = ChatRepository().sendMessage(
                endpoint = server.url("/").toString(),
                credential = credential,
                message = "привет",
                model = "local-pick",
                modelProvider = "local:provider",
                explicitModelPick = true,
            ).toList()
            val started = events.filterIsInstance<ChatEvent.StreamStarted>().single()
            assertNull(started.model)
            assertNull(started.provider)
            // Сам запрос выбор несёт (контракт отправки не сломан).
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"model\":\"local-pick\""))
        }
    }

    @Test
    fun attachToStreamLocalStreamStartedHasNoModelBecauseNothingWasReported() = runBlocking {
        // GET-подключение кадра начала хода не имеет: честный null вместо догадки.
        MockWebServer().use { server ->
            enqueueSse(server)
            val events = ChatRepository()
                .attachToStream(server.url("/").toString(), credential, "st-9")
                .toList()
            val started = events.filterIsInstance<ChatEvent.StreamStarted>().single()
            assertEquals("st-9", started.streamId)
            assertNull(started.model)
            assertNull(started.provider)
        }
    }

    // ---- обратная совместимость sealed-потока --------------------------------------------------

    @Test
    fun legacyConstructionAndEqualityStillWork() {
        // Старые места конструкции (один аргумент) и равенство с дефолтами не сломаны:
        // исчерпывающие when в app-mobile/app-tv добавление полей не роняет.
        assertEquals(ChatEvent.StreamStarted("st-1"), ChatEvent.StreamStarted("st-1", null, null))
        assertTrue(ChatEvent.StreamStarted("st-1") is ChatEvent)
    }
}
