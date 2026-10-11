package ru.mars.mobile

/**
 * Отличает ТРАНСПОРТНЫЕ обрывы стрима от СЕРВЕРНЫХ ошибок хода/инструментов.
 *
 * Обе категории приходят одним типом [ru.mars.network.model.ChatEvent.Error], но смысл
 * противоположный:
 * - транспортный обрыв (сеть переключилась, прокси убил сокет, HTTP-отказ вместо стрима) —
 *   ход на сервере ЖИВ, сообщение доставлено, нужно восстанавливать стрим через
 *   `GET /api/chat/stream` c `Last-Event-ID` (никогда не повторять `POST /api/chat/start`);
 * - серверный `error`/`apperror`-кадр (tool error: «command not found», «File not found: …») —
 *   это журнал хода, стрим может оставаться живым, а сам ход завершается ошибкой. Такой
 *   текст обязан дойти до транскрипта и НЕ должен запускать recovery-цикл.
 *
 * core-network менять нельзя, поэтому seam чисто текстовый: транспортные сообщения
 * производятся только двумя internal-функциями ядра — `describeHttpError` (формат
 * `"HTTP <code>[: reason]"`, закреплён контрактом в его KDoc и используется
 * `StreamRecoveryBackoffPolicy.HTTP_CODE_REGEX`/`StreamReattachPolicy`) и
 * `describeNetworkError` (фиксированный набор русских фраз по типам исключений).
 * Всё, что не совпало с этими формами, считается серверной ошибкой хода — консервативная
 * классификация: лучше не пытаться восстановить стрим, чем подавить видимую ошибку
 * инструмента и ретраить заведомо мёртвый ход.
 *
 * Чистый JVM-класс без Android/корутина — покрыт [TransportFailureClassifierTest].
 */
class TransportFailureClassifier {

    /** `true`, когда сообщение [ru.mars.network.model.ChatEvent.Error] имеет транспортную форму. */
    fun isTransportShaped(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val normalized = message.trim().lowercase()
        if (HTTP_STATUS_PREFIX.containsMatchIn(normalized)) return true
        if (normalized == EMPTY_BODY_MESSAGE) return true
        return NETWORK_MESSAGE_PREFIXES.any { normalized.startsWith(it) }
    }

    companion object {
        /**
         * `describeHttpError`: `"HTTP <code>"` / `"HTTP <code>: <reason>"` — всегда в начале
         * сообщения. Совпадает с якорем `StreamReattachPolicy.HTTP_404_PREFIX` (расширен до
         * любого кода: 401/5xx/429 — тоже транспорт, их разбирает уже координатор).
         */
        private val HTTP_STATUS_PREFIX = Regex("""^http\s*\d{3}\b""")

        /**
         * Фразы `describeNetworkError` по типам исключений (SocketTimeout, UnknownHost, SSL,
         * Connect, Socket, EOF, InterruptedIO, прочий IOException, fallback без message).
         * Список зеркалит core-network/NetworkErrors.kt; расхождение покрыто тестом-эталоном.
         */
        private val NETWORK_MESSAGE_PREFIXES = listOf(
            "превышено время ожидания",
            "сервер недоступен: не удалось определить адрес",
            "ошибка защищённого соединения (ssl)",
            "не удалось подключиться к серверу",
            "соединение прервано",
            "сервер неожиданно закрыл соединение",
            "сетевая ошибка:",
            "неизвестная ошибка сети",
        )

        /** 200 с пустым телом (`ChatRepository.emitFrames`): стрим не открылся — транспорт. */
        private const val EMPTY_BODY_MESSAGE = "пустой ответ сервера"
    }
}
