package ru.mars.mobile.widget

/**
 * Политика виджетов рабочего стола — чистая JVM (без android-импортов).
 *
 * Зачем политика, а не логика в провайдере: провайдер виджета исполняется в процессе
 * лаунчера, его нельзя проверить обычным unit-тестом, а RemoteViews молча игнорирует
 * всё, чего не понял. Поэтому здесь живёт ВСЁ, что можно решить без Android:
 *
 * 1. **Контракт действия** — единственное имя extra ([EXTRA_WIDGET_ACTION]) и три
 *    значения ([ACTION_COMPOSE], [ACTION_VOICE], [ACTION_NEW_CHAT]). Строки вынесены
 *    в константы, потому что их пишет виджет (в процессе лаунчера) и читает
 *    ChatActivity (в своём процессе): расхождение в одной букве = «тап по микрофону
 *    открывает чат и молчит».
 * 2. **Состояние карточки** ([MarsWidgetState]) — что показывать в верхней части:
 *    имя последнего диалога и превью последнего ответа агента. Состояние приходит из
 *    SharedPreferences, где его оставил ChatActivity; пустое/битое значение обязано
 *    превращаться в понятную подсказку, а не в пустую строку (виджет без текста
 *    выглядит сломанным).
 * 3. **Форматирование под виджет**: одна строка, без markdown-мусора и без обрезки
 *    посередине слова. Текст ответа агента — это markdown с заголовками, звёздочками
 *    и бэктиками; в 2 строки виджета он должен попадать чистым.
 *
 * Ничего платформенного здесь нет намеренно: [MarsWidgetActionsPolicyTest] проверяет
 * и маршрутизацию, и форматирование обычным JUnit'ом.
 */
object MarsWidgetActionsPolicy {

    // --- Контракт действия (extra интента ChatActivity) -------------------------------------

    /** Имя extra: что именно сделать сразу после открытия экрана чата. */
    const val EXTRA_WIDGET_ACTION = "extra_widget_action"

    /** Открыть чат и сразу поднять клавиатуру в поле ввода. */
    const val ACTION_COMPOSE = "compose"

    /** Открыть чат и сразу начать запись голоса (диктовка одной фразой). */
    const val ACTION_VOICE = "voice"

    /** Открыть чат и мгновенно начать новый чистый диалог. */
    const val ACTION_NEW_CHAT = "new_chat"

    // --- Ключи состояния виджета (SharedPreferences) ----------------------------------------

    /** Файл состояния: пишет ChatActivity, читает провайдер виджета. */
    const val STATE_PREFS = "mars_widget_state"

    const val KEY_SESSION_ID = "session_id"
    const val KEY_SESSION_TITLE = "session_title"
    const val KEY_LAST_SNIPPET = "last_snippet"
    const val KEY_UPDATED_AT = "updated_at"

    // --- Тексты по умолчанию ----------------------------------------------------------------

    /** Заголовок, когда активного диалога нет (или он без имени). */
    const val DEFAULT_TITLE = "Готов к работе"

    /** Подсказка вместо превью, когда агент ещё ничего не ответил. */
    const val DEFAULT_SNIPPET = "Нажмите, чтобы продолжить диалог или задать вопрос"

    /** Предел имени диалога в виджете: одна строка 13sp, дальше — многоточие. */
    const val MAX_TITLE_CHARS = 28

    /** Предел превью ответа: две строки 11sp — примерно столько и влезает. */
    const val MAX_SNIPPET_CHARS = 110

    /** Символ обрезки: тот же, что у `ellipsize="end"` в разметке. */
    const val ELLIPSIS = "…"

    /** Что виджет должен сделать по значению extra. */
    enum class WidgetAction {
        /** Сфокусировать поле ввода и показать клавиатуру. */
        COMPOSE,

        /** Сразу включить голосовой ввод. */
        VOICE,

        /** Начать новый чистый диалог (и открыть клавиатуру). */
        NEW_CHAT,

        /** Действия нет (обычный запуск приложения, push, deep-link). */
        NONE,
    }

    /**
     * Состояние верхней части виджета.
     *
     * [sessionId] — адресат тапа по блоку диалога: `null` означает «диалога ещё нет»,
     * и тап открывает новый чат с клавиатурой вместо несуществующей сессии.
     */
    data class MarsWidgetState(
        val sessionId: String? = null,
        // Дефолты — через полное имя объекта: вложенный класс не видит члены внешнего
        // `object` без квалификации (в отличие от companion object).
        val title: String = MarsWidgetActionsPolicy.DEFAULT_TITLE,
        val snippet: String = MarsWidgetActionsPolicy.DEFAULT_SNIPPET,
        val updatedAt: Long = 0L,
    ) {
        /** Есть ли куда вести тап: id непустой и не из пробелов. */
        val hasSession: Boolean get() = !sessionId.isNullOrBlank()
    }

    /**
     * Значение extra → действие. Неизвестная строка, `null` и пустое значение — [WidgetAction.NONE]:
     * обычный запуск чата (из списка диалогов, push, deep-link) не должен ничего «делать сам».
     */
    fun actionFor(raw: String?): WidgetAction = when (raw?.trim()?.lowercase()) {
        ACTION_COMPOSE -> WidgetAction.COMPOSE
        ACTION_VOICE -> WidgetAction.VOICE
        ACTION_NEW_CHAT -> WidgetAction.NEW_CHAT
        else -> WidgetAction.NONE
    }

    /** Обратное отображение: действие → значение extra (для провайдера и тестов). */
    fun extraValue(action: WidgetAction): String? = when (action) {
        WidgetAction.COMPOSE -> ACTION_COMPOSE
        WidgetAction.VOICE -> ACTION_VOICE
        WidgetAction.NEW_CHAT -> ACTION_NEW_CHAT
        WidgetAction.NONE -> null
    }

    /**
     * Сырые значения из SharedPreferences → состояние для отрисовки.
     *
     * Пустой id диалога нормализуется в `null` (иначе тап повёл бы в несуществующую
     * сессию), пустое имя — в [DEFAULT_TITLE], пустое превью — в [DEFAULT_SNIPPET].
     */
    fun state(
        sessionId: String?,
        rawTitle: String?,
        rawSnippet: String?,
        updatedAt: Long = 0L,
    ): MarsWidgetState = MarsWidgetState(
        sessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() },
        title = title(rawTitle),
        snippet = snippet(rawSnippet),
        updatedAt = updatedAt,
    )

    /** Имя диалога для виджета: одна строка, без markdown-шума, с дефолтом и обрезкой. */
    fun title(raw: String?): String = fit(clean(raw), MAX_TITLE_CHARS, DEFAULT_TITLE)

    /** Превью последнего ответа агента: та же чистка, но длиннее и со своим дефолтом. */
    fun snippet(raw: String?): String = fit(clean(raw), MAX_SNIPPET_CHARS, DEFAULT_SNIPPET)

    /**
     * Время последнего обновления «HH:mm» (локальная зона) — маленькая подпись в верхней
     * строке. `0`/отрицательное значение означает «состояния ещё не было» и даёт пустую
     * строку: пустая подпись не занимает места, а «00:00» выглядело бы как поломка.
     */
    fun updatedLabel(timestampMs: Long, timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        if (timestampMs <= 0L) return ""
        // Locale.ROOT, а не getDefault(): подпись — это ЧИСЛО времени, а не текст, и в
        // локалях с другими цифрами (арабская, тайская) «22:13» превратилось бы в
        // нечитаемый набор глифов рядом с русским именем диалога.
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
        format.timeZone = timeZone
        return format.format(java.util.Date(timestampMs))
    }

    /**
     * Чистка текста под виджет: markdown → плоская строка.
     *
     * Что убирается и почему:
     * - переносы строк и табы → пробел (в виджете 1–2 строки, `\n` съел бы обе);
     * - блоки кода ``` и инлайн-бэктики — в превью они выглядят как мусорные кавычки;
     * - `#`, `*`, `_`, `>` — заголовки, списки, выделение и цитаты;
     * - повторные пробелы схлопываются, края обрезаются.
     */
    private fun clean(raw: String?): String {
        val text = raw.orEmpty()
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace("```", " ")
            .replace(Regex("[*_`>#]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text
    }

    /**
     * Обрезка до [max] символов с многоточием. Режется по границе слова, если она есть
     * не дальше последних 12 символов: «Спросил про виджеты и получил…» читается лучше,
     * чем «Спросил про виджеты и получ…».
     */
    private fun fit(text: String, max: Int, fallback: String): String {
        if (text.isEmpty()) return fallback
        if (text.length <= max) return text
        val head = text.take(max - 1)
        val cut = head.lastIndexOf(' ')
        val body = if (cut >= max - 13) head.take(cut) else head
        return body.trimEnd().trimEnd(',', ':', ';', '.', '—', '-') + ELLIPSIS
    }
}
