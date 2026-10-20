package ru.mars.network.repository

import ru.mars.network.model.AgentFinishedReason
import ru.mars.network.model.VoiceEvent

/**
 * Разбор SSE-потока голосового шлюза (`GET api/voice/stream`) в [VoiceEvent].
 *
 * Разделение ответственности то же, что у [KanbanStreamParser]: [SseChunkParser] —
 * транспорт (фрагментация по TCP, BOM, CRLF, комментарии-keepalive), этот класс —
 * ЧИСТОЕ отображение кадра в модель. Никаких Android-импортов, никакого I/O: парсер
 * тестируется на JVM без сокета.
 *
 * Контракт кадра: `id: <voice_id>:<seq>`, `event: <имя>`, `data: <json>`, пустая
 * строка. [lastEventId] хранит последний непустой `id:` — ровно то значение, которое
 * клиент отдаёт обратно заголовком `Last-Event-ID` при переподключении.
 *
 * Терпимость к расширению сервера: неизвестное имя события -> [VoiceEvent.Unknown]
 * (видно, что поток жив и что сервер вырос), битый или пустой JSON -> тоже
 * [VoiceEvent.Unknown], а не исключение: один испорченный кадр не должен убивать
 * весь голосовой поток. Кадры без `data` (комментарии `: keepalive`, голый `id:`)
 * отбрасывает ещё [SseChunkParser].
 */
internal class VoiceStreamParser {

    private val chunkParser = SseChunkParser()

    /** Последний непустой `id:` кадра — курсор докачки для `Last-Event-ID`. */
    var lastEventId: String? = null
        private set

    /** Принимает очередной кусок декодированного текста, возвращает готовые события. */
    fun feed(chunk: String): List<VoiceEvent> = map(chunkParser.feed(chunk))

    /** Конец потока: дочитывает буферизованный незавершённый кадр. */
    fun end(): List<VoiceEvent> = map(chunkParser.end())

    private fun map(frames: List<SseChunkParser.Frame>): List<VoiceEvent> =
        frames.mapNotNull { frame ->
            frame.id?.trim()?.takeIf { it.isNotEmpty() }?.let { lastEventId = it }
            parse(frame)
        }

    companion object {

        /**
         * Отображает один кадр. Возвращает null только для кадра без данных
         * (такой не несёт ничего отображаемого).
         */
        fun parse(frame: SseChunkParser.Frame): VoiceEvent? {
            val name = frame.event?.trim().orEmpty()
            val data = frame.data
            if (data.isBlank()) return null
            val payload = TolerantJson.parseMap(data)
            if (payload == null) {
                // Битый JSON: имя события известно, но полезной нагрузки нет. Не бросаем —
                // помечаем как Unknown, чтобы вызывающий код мог залогировать дрейф.
                return VoiceEvent.Unknown(name, data)
            }
            return when (name) {
                "session.ready" -> VoiceEvent.SessionReady(
                    voiceId = TolerantJson.string(payload, "voice_id").orEmpty(),
                    sessionId = TolerantJson.string(payload, "session_id").orEmpty(),
                    protocol = TolerantJson.string(payload, "protocol"),
                    serverEvents = TolerantJson.stringListOrEmpty(payload, "server_events"),
                    keepaliveSeconds = TolerantJson.longOrNull(payload, "keepalive_seconds"),
                    resumeRequested = TolerantJson.bool(payload, "resume_requested"),
                    resumeStatus = TolerantJson.string(payload, "resume_status"),
                )

                "transcript.partial" -> VoiceEvent.TranscriptPartial(
                    text = TolerantJson.string(payload, "text").orEmpty(),
                    sessionId = TolerantJson.string(payload, "session_id"),
                )

                "transcript.final" -> VoiceEvent.TranscriptFinal(
                    text = TolerantJson.string(payload, "text").orEmpty(),
                    sessionId = TolerantJson.string(payload, "session_id"),
                    // Контракт помечает кадр как финальный; отсутствие поля трактуем
                    // как final=true (иначе финальный транскрипт потеряется).
                    final = TolerantJson.bool(payload, "final", default = true),
                )

                "agent.started" -> VoiceEvent.AgentStarted(
                    sessionId = TolerantJson.string(payload, "session_id"),
                    message = TolerantJson.string(payload, "message"),
                )

                "agent.text.delta" -> VoiceEvent.AgentTextDelta(
                    text = TolerantJson.string(payload, "text").orEmpty(),
                )

                "tool.started" -> VoiceEvent.ToolStarted(
                    name = TolerantJson.string(payload, "name").orEmpty(),
                    preview = TolerantJson.string(payload, "preview"),
                    toolCallId = TolerantJson.string(payload, "tool_call_id"),
                )

                "tool.finished" -> VoiceEvent.ToolFinished(
                    name = TolerantJson.string(payload, "name").orEmpty(),
                    preview = TolerantJson.string(payload, "preview"),
                    toolCallId = TolerantJson.string(payload, "tool_call_id"),
                    isError = TolerantJson.bool(payload, "is_error"),
                    duration = TolerantJson.doubleOrNull(payload, "duration"),
                )

                "agent.finished" -> VoiceEvent.AgentFinished(
                    reason = AgentFinishedReason.fromWire(TolerantJson.string(payload, "reason")),
                    sessionId = TolerantJson.string(payload, "session_id"),
                    message = TolerantJson.string(payload, "message"),
                    usage = TolerantJson.mapField(payload, "usage"),
                    terminalState = TolerantJson.mapField(payload, "terminal_state"),
                )

                "speech.stop" -> VoiceEvent.SpeechStop(
                    reason = TolerantJson.string(payload, "reason"),
                    at = TolerantJson.longOrNull(payload, "at"),
                    runId = TolerantJson.string(payload, "run_id"),
                    sessionId = TolerantJson.string(payload, "session_id"),
                )

                else -> VoiceEvent.Unknown(name, data)
            }
        }
    }
}
