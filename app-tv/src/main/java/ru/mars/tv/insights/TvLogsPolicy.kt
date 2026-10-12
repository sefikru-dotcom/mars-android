package ru.mars.tv.insights

import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail
import ru.mars.network.model.ServerLogWindow
import java.util.Locale

/**
 * Pure presentation policy of the TV «Логи» tab (no Android, no I/O —
 * JVM-testable; wording is mobile `LogsPolicy` parity). Экран СТРОГО READ-ONLY:
 * единственный сетевой вызов — `GET /api/logs?file=&tail=` (read-only белый
 * список нативного токена); оба параметра whitelisted на сервере
 * ([ServerLogFile]/[ServerLogTail] зеркалируют whitelist — запрос не может
 * стать malformed).
 *
 * Правила отрисовки окна:
 * - пустой файл с `hint` — валидное состояние («файла ещё нет»), не ошибка;
 * - `truncated` — отдельная notice-строка (сервер обрезал хвост старше 4 MiB);
 * - `total_bytes`/`mtime` — только когда сервер их отдал (нулевой размер не
 *   выдумывается).
 */
object TvLogsPolicy {

    /** [restoreSelection] result when there is nothing to focus. */
    const val NO_SELECTION = -1

    /** Опции селектора файлов (whitelist сервера) — mobile parity. */
    val FILE_OPTIONS: List<ServerLogFile> = ServerLogFile.entries

    /** Опции селектора хвоста (whitelist сервера: 100/200/500/1000). */
    val TAIL_OPTIONS: List<ServerLogTail> = ServerLogTail.entries

    /** Опции по умолчанию (whitelist сервера) — mobile `LogsPolicy` parity. */
    val DEFAULT_FILE: ServerLogFile = ServerLogFile.AGENT
    val DEFAULT_TAIL: ServerLogTail = ServerLogTail.TAIL_200

    const val NOTICE_TRUNCATED = "Показаны последние строки — более старые обрезаны сервером"

    const val ERROR_UNAUTHORIZED = "Логи недоступны: нет доступа"
    const val ERROR_REQUEST = "Логи недоступны: некорректный запрос"
    const val ERROR_GENERIC = "Логи недоступны"

    /** Максимальный возраст кэшированного окна логов — операционные данные (12 ч). */
    const val CACHE_MAX_AGE_MS = 12L * 60 * 60 * 1000

    /** Подпись опции файла: «Агент»/«Ошибки»/«Шлюз» — mobile parity. */
    fun fileLabel(file: ServerLogFile): String = when (file) {
        ServerLogFile.AGENT -> "Агент"
        ServerLogFile.ERRORS -> "Ошибки"
        ServerLogFile.GATEWAY -> "Шлюз"
    }

    /** Подпись опции хвоста: «100 строк» и т.д. — mobile parity. */
    fun tailLabel(tail: ServerLogTail): String = "${tail.lines} строк"

    /**
     * Мета-строка окна: размер файла (+ «обрезано») и время изменения;
     * `mtime == null` (файла нет) даёт пустую строку — mobile parity.
     */
    fun meta(window: ServerLogWindow): String = buildString {
        append(formatBytes(window.totalBytes))
        if (window.truncated) append(" · обрезано")
        val mtime = window.mtime ?: return@buildString
        append(" · изменено ")
        append(java.time.Instant.ofEpochSecond(mtime.toLong()))
    }

    /** Человекочитаемый размер: B/KiB/MiB/GiB, целым для больших значений. */
    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
        else -> "${bytes / (1024L * 1024 * 1024)} GiB"
    }

    /**
     * Пустое состояние окна: `hint` сервера («Log file … not found yet.») когда
     * файл ещё не создан, иначе честное «Файл пока пуст».
     */
    fun emptyMessage(window: ServerLogWindow): String =
        window.hint.ifBlank { "Файл пока пуст" }

    /** Заголовок вкладки со счётчиком строк — мобильная «Обновить (N строк)» в TV-формулировке. */
    fun windowTitle(window: ServerLogWindow): String =
        "Файл ${window.file.ifBlank { "?" }} · ${window.lines.size} строк"

    /**
     * Focus restoration after a re-render (file/tail switch, auto-refresh): the
     * previous index clamped into the new list — edges STAY, never wrap;
     * [NO_SELECTION] when the list is empty.
     */
    fun restoreSelection(previous: Int, size: Int): Int =
        if (size <= 0) NO_SELECTION else previous.coerceIn(0, size - 1)
}
