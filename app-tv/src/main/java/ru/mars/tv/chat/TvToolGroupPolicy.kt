package ru.mars.tv.chat

/**
 * Сводка вызовов инструментов для ТВ-истории (чистая политика, JVM-тесты).
 *
 * Разрыв: [ru.mars.tv.TvSessionBrowserPolicy.transcript] рисовал каждую строку
 * `role:"tool"` отдельным пузырём «Марс» с сырым выводом инструмента. На 10 футов
 * это нечитаемо: десяток «ответов Марса», в которых нет ни одного слова Марса, и
 * среди них теряется настоящий ответ. Мобильный клиент сворачивает такие вызовы в
 * `ToolExecution` + строку-сводку ([ru.mars.mobile.ToolGroupPresenter]); здесь —
 * тот же смысл, но под пульт: ОДНА компактная карточка на непрерывную серию
 * вызовов, без раскрытия и без сырого вывода.
 *
 * Правила:
 * - серия подряд идущих `role:"tool"` строк — одна группа (порядок сохраняется);
 * - в группе только имена инструментов и счётчик, текст вывода не показывается
 *   вовсе (это не речь Марса и не текст для дивана);
 * - одинаковые имена не повторяются: `read_file x4`, `grep`;
 * - больше [MAX_NAMES] имён — хвост сворачивается в «и ещё N»;
 * - статус в истории всегда «Готово»: персистентный транскрипт — это уже
 *   завершённые вызовы (живой ход показывает статус отдельной строкой активности).
 */
object TvToolGroupPolicy {

    /** Автор карточки группы — не «Марс»: это не ответ модели. */
    const val TOOL_AUTHOR = "Инструменты"

    /** С какого числа вызовов серия читается как группа (одна карточка на серию). */
    const val GROUP_THRESHOLD = 2

    /** Сколько имён инструментов показывать до свёртки «и ещё N». */
    const val MAX_NAMES = 4

    /** Статус завершённой серии в персистентной истории. */
    const val DONE_SUFFIX = "Готово"

    /** Имя инструмента, когда сервер его не прислал: догадок не выдумываем. */
    const val UNKNOWN_TOOL = "инструмент"

    /** «4 инструмента · Готово». */
    fun summary(count: Int): String = "$count ${plural(count)} · $DONE_SUFFIX"

    /** Русская форма слова «инструмент» (1 инструмент, 2 инструмента, 11 инструментов). */
    fun plural(count: Int): String {
        val abs = kotlin.math.abs(count)
        val mod100 = abs % 100
        if (mod100 in 11..14) return "инструментов"
        return when (abs % 10) {
            1 -> "инструмент"
            2, 3, 4 -> "инструмента"
            else -> "инструментов"
        }
    }

    /**
     * Строка имён: `read_file x4, grep, write_file и ещё 2`.
     * Пустые имена схлопываются в [UNKNOWN_TOOL], чтобы группа не выглядела пустой.
     */
    fun namesLine(names: List<String>, maxShown: Int = MAX_NAMES): String {
        if (names.isEmpty()) return ""
        val counts = LinkedHashMap<String, Int>()
        names.forEach { raw ->
            val name = raw.trim().takeIf { it.isNotEmpty() } ?: UNKNOWN_TOOL
            counts[name] = (counts[name] ?: 0) + 1
        }
        val shown = counts.entries.take(maxShown.coerceAtLeast(1)).map { (name, times) ->
            if (times > 1) "$name x$times" else name
        }
        val hidden = counts.size - shown.size
        return if (hidden > 0) shown.joinToString(", ") + " и ещё $hidden" else shown.joinToString(", ")
    }

    /**
     * Имя инструмента строки истории: wire `tool_name`, иначе null. Пустое/пробельное
     * значение — тоже null (сервер мог прислать `""`).
     */
    fun toolName(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
