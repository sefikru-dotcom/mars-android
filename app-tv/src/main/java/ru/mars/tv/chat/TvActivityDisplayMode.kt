package ru.mars.tv.chat

import ru.mars.network.model.ChatEvent

/**
 * Режимы показа хода на ТВ — паритет с мобильным `ActivityDisplayMode` и переключателем
 * WebUI (`stream_full` / `stream_window` / `stream_quiet` из GET /api/settings).
 * Проводные значения СОБИРАЮТ ту же строку, что и мобильский enum: одна серверная
 * семантика на все клиенты.
 *
 * ТВ-семантика (в мобильном режиме управляют раскрытием мыслей/инструментов в пузыре;
 * на ТВ мысли живут в ленте и статус-полосе):
 * - [STREAM_FULL]: каждая мысль и каждый инструмент дублируются строкой в транскрипт.
 * - [STREAM_WINDOW] (по умолчанию): только статус-полоса («Марс размышляет…»,
 *   «Выполняет: …») — прежнее поведение ТВ.
 * - [STREAM_QUIET]: детали хода скрыты, статус-полоса во время хода краткая.
 *
 * Чистый enum + порт хранения: вся логика режимов покрыта JVM-тестами, Android-код —
 * только SharedPreferences-адаптер ([TvActivityModePrefs]) и тонкий рендер кнопки.
 */
enum class TvActivityDisplayMode(val wire: String, val label: String, val description: String) {
    STREAM_FULL("stream_full", "Всё", "Мысли и инструменты видны в ленте"),
    STREAM_WINDOW("stream_window", "Окно", "Текущий шаг — в статус-полосе"),
    STREAM_QUIET("stream_quiet", "Тишина", "Ход без деталей, статус краткий");

    /** Следующий режим фиксированного цикла FULL -> WINDOW -> QUIET -> FULL. */
    fun next(): TvActivityDisplayMode = when (this) {
        STREAM_FULL -> STREAM_WINDOW
        STREAM_WINDOW -> STREAM_QUIET
        STREAM_QUIET -> STREAM_FULL
    }

    companion object {
        /** Режим WebUI по умолчанию. */
        val DEFAULT: TvActivityDisplayMode = STREAM_WINDOW

        /** Разбор проводного значения из настроек/SharedPreferences; неизвестное/пустое -> [DEFAULT]. */
        fun fromWire(value: String?): TvActivityDisplayMode =
            values().firstOrNull { it.wire == value?.trim() } ?: DEFAULT
    }
}

/** Порт персистентности [TvActivityDisplayMode]; приложение реализует его через SharedPreferences. */
interface TvActivityModeStorage {
    fun load(): TvActivityDisplayMode
    fun save(mode: TvActivityDisplayMode)
}

/**
 * Доменный держатель эффективного режима (UI -> состояние -> персистентность), зеркалит
 * мобильский `ActivityModeSelection`: восстанавливает сохранённый выбор, сохраняет
 * изменения пользователя и отдаёт подпись кнопки в шапке. Про Android-вьюхи не знает.
 */
class TvActivityModeSelection(private val storage: TvActivityModeStorage) {

    /** Эффективный режим: сохранённый выбор или [TvActivityDisplayMode.DEFAULT]. */
    var current: TvActivityDisplayMode = storage.load()
        private set

    /** Сохраняет выбор пользователя и возвращает его. */
    fun select(mode: TvActivityDisplayMode): TvActivityDisplayMode {
        current = mode
        storage.save(mode)
        return mode
    }

    /** Переключает на следующий режим, сохраняет и возвращает новый режим. */
    fun cycle(): TvActivityDisplayMode = select(current.next())

    /** Короткая подпись для кнопки шапки («Всё», «Окно», «Тишина»). */
    fun buttonLabel(): String = current.label
}

/**
 * Чистая политика показа хода для ТВ: что режим делает с событиями Thought/ToolCall/
 * ToolResult (строки транскрипта) и со статус-полосой (краткость в «Тишине»).
 */
object TvActivityDisplayPolicy {

    /** Краткий статус «Тишины» на время живого хода. */
    const val QUIET_LIVE_STATUS = "Марс работает…"

    /** Полная подпись кнопки шапки: «Режим: Окно». */
    fun buttonCaption(mode: TvActivityDisplayMode): String = "Режим: ${mode.label}"

    /** Строка, которую режим смены опубликует в статус-полосе. */
    fun switchNotice(mode: TvActivityDisplayMode): String = "Режим «${mode.label}» — ${mode.description}"

    /**
     * Строка транскрипта для события хода; null, когда режим не дублирует ход в ленту.
     * Только [TvActivityDisplayMode.STREAM_FULL] пишет мысли/инструменты в транскрипт.
     */
    fun transcriptLine(mode: TvActivityDisplayMode, event: ChatEvent): String? {
        if (mode != TvActivityDisplayMode.STREAM_FULL) return null
        return when (event) {
            is ChatEvent.Thought -> event.text.trim().takeIf { it.isNotEmpty() }?.let { "Мысль: $it" }
            is ChatEvent.ToolCall -> "Инструмент: ${event.name}"
            is ChatEvent.ToolResult -> "Готово: ${event.name}"
            else -> null
        }
    }

    /**
     * Текст статус-полосы с учётом режима. «Тишина» на живом ходу ([turnLive]) глушит
     * ДЕТАЛИ хода (мысли/инструменты/печать/фазы) до одной краткой строки; явные статусы
     * (ответ получен, ошибка, «уточнение отправлено») событие хода не несёт — они проходят
     * как есть, потому что [lastEvent] для них null или терминальный.
     */
    fun status(
        mode: TvActivityDisplayMode,
        turnLive: Boolean,
        lastEvent: ChatEvent?,
        fallback: String,
    ): String {
        if (mode != TvActivityDisplayMode.STREAM_QUIET || !turnLive) return fallback
        return if (isActivityDetail(lastEvent)) QUIET_LIVE_STATUS else fallback
    }

    /** Событие, описывающее деталь хода (а не явный вердикт/ответ пользователю). */
    fun isActivityDetail(event: ChatEvent?): Boolean = when (event) {
        is ChatEvent.Thought, is ChatEvent.ToolCall, is ChatEvent.ToolResult,
        is ChatEvent.Chunk, is ChatEvent.Phase,
        -> true
        else -> false
    }
}
