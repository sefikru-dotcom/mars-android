package ru.mars.mobile

import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail
import ru.mars.network.model.ServerLogWindow

/**
 * Фаза вкладки Logs экрана мониторинга. Чистая JVM-модель — UI-слой
 * (InsightsActivity) только сопоставляет фазу с видимостью прогресса/строк/
 * статуса, все тексты считает [LogsPolicy].
 */
enum class LogsPhase {
    /** Первая загрузка или смена файла/tail: строки могут быть прошлыми. */
    LOADING,

    /** Окно загружено, есть хотя бы одна строка лога. */
    READY,

    /** Файл пуст или ещё не создан на сервере (валидный 200 с `hint`). */
    EMPTY,

    /** Лог недоступен (сеть/сервер). Возможны offline-safe строки прошлого окна. */
    ERROR,

    /** Нет сохранённой пары (credential): загрузка невозможна до входа. */
    NO_AUTH,
}

/**
 * Полное состояние вкладки Logs: фаза, выбранные whitelisted [file]/[tail]
 * (только enum'ы сервера — произвольные пути логов не представимы типом),
 * строки окна, [meta]-подпись, внеполосное [notice], центральный [message]
 * и флаг [canRetry].
 */
data class LogsUiState(
    val phase: LogsPhase,
    val file: ServerLogFile = LogsPolicy.DEFAULT_FILE,
    val tail: ServerLogTail = LogsPolicy.DEFAULT_TAIL,
    val lines: List<String> = emptyList(),
    val meta: String? = null,
    val notice: String? = null,
    val message: String? = null,
    val canRetry: Boolean = false,
)

/**
 * Чистая политика вкладки Logs (UI -> state/domain -> network): тексты,
 * подписи whitelisted файла/tail и формат окна. Не знает ни про Android, ни
 * про сеть — данные приходят из [ru.mars.network.repository.LogsRepository]
 * через [LogsPresenter].
 *
 * Контракт whitelist'а: и файл, и tail — серверные enum'ы
 * [ServerLogFile]/[ServerLogTail] ({agent, errors, gateway} × {100, 200, 500,
 * 1000}); политика принимает только их, поэтому запрос через экран не может
 * унести произвольный путь или размер.
 */
object LogsPolicy {

    val FILE_OPTIONS: List<ServerLogFile> = ServerLogFile.values().toList()
    val TAIL_OPTIONS: List<ServerLogTail> = ServerLogTail.values().toList()

    val DEFAULT_FILE = ServerLogFile.AGENT
    val DEFAULT_TAIL = ServerLogTail.TAIL_200

    const val MSG_LOADING = "Загрузка лога…"
    const val MSG_EMPTY = "Файл пока пуст"
    const val MSG_ERROR = "Логи недоступны. Проверьте соединение и повторите."
    const val MSG_ERROR_OFFLINE = "Нет сети: логи недоступны"
    const val MSG_NO_AUTH = "Нужен вход: откройте приложение и пройдите пару"
    const val NOTICE_OFFLINE = "Нет сети"
    const val NOTICE_OFFLINE_STALE = "Нет сети — показаны последние строки"
    const val NOTICE_TRUNCATED = "Файл больше 4 MiB: показан обрезанный хвост"
    const val RETRY_LABEL = "Повторить"

    /** 400 `Unknown log file` — недостижимо при enum-выборе, но честно показывается. */
    fun msgInvalidRequest(serverMessage: String): String =
        "Сервер отклонил файл лога: ${serverMessage.ifBlank { "400" }}"

    /** Подпись чипа файла (короткая, без глифов). */
    fun fileLabel(file: ServerLogFile): String = when (file) {
        ServerLogFile.AGENT -> "Агент"
        ServerLogFile.ERRORS -> "Ошибки"
        ServerLogFile.GATEWAY -> "Шлюз"
    }

    /** Подпись чипа tail: «200 строк» (все whitelisted значения склоняются одинаково). */
    fun tailLabel(tail: ServerLogTail): String = "${tail.lines} строк"

    /**
     * Подпись окна: число строк и размер файла. [ServerLogWindow.tail] —
     * фактический tail, который применил сервер (может отличаться от запроса
     * при не-whitelisted значении — у нас невозможен, но подпись честная).
     */
    fun meta(window: ServerLogWindow): String =
        "строк: ${window.lines.size} · ${formatBytes(window.totalBytes)}"

    /** Человекочитаемый размер: Б / КБ / МБ, одна цифра после точки от КБ. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "0 Б"
        if (bytes < 1024L) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return "${oneDecimal(kb)} КБ"
        return "${oneDecimal(kb / 1024.0)} МБ"
    }

    private fun oneDecimal(value: Double): String {
        val tenth = ((value * 10).toLong() % 10).let { if (it < 0) -it else it }
        return "${value.toLong()}.$tenth"
    }

    /** Текст EMPTY: серверный `hint` (файл ещё не создан) важнее общей строки. */
    fun emptyMessage(window: ServerLogWindow): String =
        window.hint.ifBlank { MSG_EMPTY }
}
