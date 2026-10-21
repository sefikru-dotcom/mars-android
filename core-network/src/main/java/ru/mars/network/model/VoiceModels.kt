package ru.mars.network.model

/**
 * Типизированные события серверного голосового шлюза (`GET api/voice/stream`, SSE).
 *
 * Шлюз только ПРОЕЦИРУЕТ обычный ход агента в голосовой словарь: сам ход стартует
 * существующим путём (`ChatRepository.transcribePcmWav` -> `sendMessage`), а сюда
 * приходит его голосовая проекция (транскрипт, дельты ответа, инструменты, стоп речи).
 *
 * Контракт терпим к расширению: неизвестное имя события и неизвестные поля НЕ являются
 * ошибкой — неизвестное имя становится [Unknown], лишние поля просто игнорируются.
 * Поэтому новый серверный event или новое поле не ломают разбор и не рвут поток.
 */
sealed class VoiceEvent {

    /**
     * `session.ready` — первый кадр потока: привязка voice_id к сессии и параметры
     * протокола. [serverEvents] — список имён событий, которые сервер умеет слать;
     * [resumeRequested]/[resumeStatus] говорят, был ли поток продолжен с Last-Event-ID.
     */
    data class SessionReady(
        val voiceId: String,
        val sessionId: String,
        val protocol: String?,
        val serverEvents: List<String>,
        val keepaliveSeconds: Long?,
        val resumeRequested: Boolean,
        val resumeStatus: String?,
    ) : VoiceEvent()

    /** `transcript.partial` — промежуточный (нестабильный) текст распознавания. */
    data class TranscriptPartial(val text: String, val sessionId: String?) : VoiceEvent()

    /** `transcript.final` — финальный текст распознавания. */
    data class TranscriptFinal(
        val text: String,
        val sessionId: String?,
        val final: Boolean,
    ) : VoiceEvent()

    /** `agent.started` — ход агента начался. */
    data class AgentStarted(val sessionId: String?, val message: String?) : VoiceEvent()

    /**
     * `agent.text.delta` — кусок текста ответа. Полный ответ = конкатенация всех
     * [AgentTextDelta.text] в порядке прихода.
     */
    data class AgentTextDelta(val text: String) : VoiceEvent()

    /** `tool.started` — агент начал вызов инструмента. */
    data class ToolStarted(
        val name: String,
        val preview: String?,
        val toolCallId: String?,
    ) : VoiceEvent()

    /** `tool.finished` — вызов инструмента завершился ([isError] — неуспехом). */
    data class ToolFinished(
        val name: String,
        val preview: String?,
        val toolCallId: String?,
        val isError: Boolean,
        val duration: Double?,
    ) : VoiceEvent()

    /**
     * `agent.finished` — ход завершён. [reason] — типизированная причина,
     * [terminalState]/[usage] — сырые серверные объекты (могут отсутствовать).
     */
    data class AgentFinished(
        val reason: AgentFinishedReason,
        val sessionId: String?,
        val message: String?,
        val usage: Map<String, Any?>?,
        val terminalState: Map<String, Any?>?,
    ) : VoiceEvent()

    /**
     * `speech.stop` — сервер просит немедленно прекратить озвучку (barge-in).
     * Ход агента при этом НЕ отменяется: это только сигнал плееру замолчать.
     */
    data class SpeechStop(
        val reason: String?,
        val at: Long?,
        val runId: String?,
        val sessionId: String?,
    ) : VoiceEvent()

    /** Неизвестное (новое для клиента) имя события: [name] + сырой [data]. */
    data class Unknown(val name: String, val data: String) : VoiceEvent()

    /**
     * Клиентский терминальный кадр: поток больше не будет продолжен, причина в [error].
     * Это НЕ серверное событие — оно появляется только когда клиент сдался
     * (терминальный HTTP-код или исчерпан бюджет переподключений).
     */
    data class StreamError(val error: VoiceGatewayError) : VoiceEvent()
}

/** Причина завершения хода из `agent.finished`; неизвестная строка -> [Unknown]. */
enum class AgentFinishedReason {
    DONE,
    CANCELLED,
    ERROR,
    STREAM_END,
    UNKNOWN;

    companion object {
        /** Разбор строки причины без исключений: `done|cancelled|error|stream_end`. */
        fun fromWire(value: String?): AgentFinishedReason = when (value?.trim()?.lowercase()) {
            "done" -> DONE
            "cancelled", "canceled" -> CANCELLED
            "error" -> ERROR
            "stream_end" -> STREAM_END
            else -> UNKNOWN
        }
    }
}

/** Ответ `POST api/voice/session`: идентификатор голосового потока и время жизни. */
data class VoiceSession(
    val voiceId: String,
    val sessionId: String,
    val expiresInSeconds: Long,
)

/** Ответ `POST api/voice/interrupt`: [interrupted] — стоп отправлен этим вызовом. */
data class VoiceInterruptResult(
    val voiceId: String,
    val interrupted: Boolean,
    val alreadyInterrupted: Boolean,
)

/** Ответ `POST api/voice/close`. */
data class VoiceCloseResult(
    val voiceId: String,
    val closed: Boolean,
)

/**
 * Типизированная ошибка голосового шлюза — вместо исключений наружу.
 *
 * [Http] несёт HTTP-код (401 — токен, 404 — неизвестная сессия, 409 — поток уже
 * занят другим подписчиком, 503 — слишком много потоков), [Transport] — сетевой
 * сбой, [Protocol] — 2xx с неразбираемым/неполным телом.
 */
sealed class VoiceGatewayError {

    /** Не-2xx ответ; [message] уже в форме `HTTP <code>: <причина>`. */
    data class Http(val code: Int, val message: String) : VoiceGatewayError()

    /** Сетевой сбой (обрыв, таймаут, DNS, TLS); [message] пригоден для показа. */
    data class Transport(val message: String, val cause: Throwable? = null) : VoiceGatewayError()

    /** 2xx, но тело не соответствует контракту. */
    data class Protocol(val message: String) : VoiceGatewayError()

    /**
     * Имеет ли смысл повторять запрос: 503/429/5xx и сетевые сбои — да;
     * 400/401/404/409 — нет (это терминальные ответы контракта).
     */
    val isRetryable: Boolean
        get() = when (this) {
            is Http -> code == 408 || code == 429 || code >= 500
            is Transport -> true
            is Protocol -> false
        }
}

/** Результат одиночного вызова шлюза: значение либо типизированная ошибка. */
sealed class VoiceResult<out T> {

    data class Success<T>(val value: T) : VoiceResult<T>()

    data class Failure(val error: VoiceGatewayError) : VoiceResult<Nothing>()
}
