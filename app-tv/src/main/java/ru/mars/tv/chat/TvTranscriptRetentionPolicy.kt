package ru.mars.tv.chat

/**
 * Чистая политика удержания транскрипта TV-чата (устойчивость к длинной истории):
 *
 * 1. **Рендер истории** ([historySlice]): при открытии/перезагрузке диалога рисуется
 *    только хвост из [MAX_RENDERED_MESSAGES] сообщений — тысячи View в контейнере на
 *    TV-приставке это OOM и секунды layout'а; скрытая голова отмечается маркером
 *    «…ранее скрыто» ([hiddenMarkerText]).
 * 2. **Строки активности** ([activityOverflow]): режим «Всё» дублирует мысли/инструменты
 *    строками в ленту; при долгом ходе их тысячи. Cap — [ACTIVITY_LINE_CAP] последних
 *    строк, самые старые снимаются, счётчик скрытых растёт.
 * 3. **Общий cap контейнера** ([trimPlan]): жёсткий потолок числа View в ленте. Подрезка
 *    идёт С НАЧАЛА (самые старые), защищённые записи (интерактивные карточки, живой
 *    пузырь, маркер скрытого) не трогаются, самая свежая запись не удаляется никогда —
 *    последние сообщения и скролл/фокус не ломаются.
 *
 * Без Android-типов: хост ([ru.mars.tv.TvChatActivity]) сам отображает View в [Kind],
 * здесь только решения — тестируется на JVM ([TvTranscriptRetentionPolicyTest]).
 */
object TvTranscriptRetentionPolicy {

    /** Чем является ребёнок контейнера ленты (абстракция View для чистых решений). */
    enum class Kind { MESSAGE, ACTIVITY_LINE, PROTECTED }

    /** Сколько последних сообщений истории рисуется при открытии/перезагрузке диалога. */
    const val MAX_RENDERED_MESSAGES = 200

    /** Потолок строк активности («Всё») в ленте; самые старые снимаются. */
    const val ACTIVITY_LINE_CAP = 200

    /** Жёсткий потолок общего числа View в контейнере ленты. */
    const val MAX_TRANSCRIPT_VIEWS = 450

    private const val HIDDEN_PREFIX = "…ранее скрыто: "

    /**
     * Срез истории под рендер: [firstRowIndex] — индекс первой рисуемой строки,
     * [renderedRows] — сколько рисуется, [hiddenMessages] — сколько скрыто сверху.
     */
    data class HistorySlice(val firstRowIndex: Int, val renderedRows: Int, val hiddenMessages: Int)

    /**
     * Хвост истории не длиннее [maxRendered]: длинные диалоги рисуются последними N
     * сообщениями, голова уходит в счётчик маркера «…ранее скрыто».
     */
    fun historySlice(totalRows: Int, maxRendered: Int = MAX_RENDERED_MESSAGES): HistorySlice {
        val total = totalRows.coerceAtLeast(0)
        val rendered = minOf(total, maxRendered.coerceAtLeast(0))
        return HistorySlice(
            firstRowIndex = total - rendered,
            renderedRows = rendered,
            hiddenMessages = total - rendered,
        )
    }

    /** Сколько САМЫХ СТАРЫХ строк активности снять сейчас, чтобы вписаться в cap. */
    fun activityOverflow(visibleActivityLines: Int, cap: Int = ACTIVITY_LINE_CAP): Int =
        (visibleActivityLines - cap.coerceAtLeast(0)).coerceAtLeast(0)

    /**
     * Индексы детей (по возрастанию) для снятия с НАЧАЛА ленты, чтобы вписаться в [cap].
     * Правила: PROTECTED не трогаются; самый последний (свежий) ребёнок не удаляется
     * никогда; удаление останавливается, как только размер вписался в cap. Если одних
     * защищённых больше cap — план пустой (best effort, потолок не гарантирован).
     */
    fun trimPlan(kinds: List<Kind>, cap: Int = MAX_TRANSCRIPT_VIEWS): List<Int> {
        val limit = cap.coerceAtLeast(1)
        if (kinds.size <= limit) return emptyList()
        val plan = mutableListOf<Int>()
        var remaining = kinds.size
        for (index in 0 until kinds.lastIndex) {
            if (remaining <= limit) break
            if (kinds[index] == Kind.PROTECTED) continue
            plan += index
            remaining--
        }
        return plan
    }

    /**
     * Текст маркера «…ранее скрыто» по счётчикам; null, когда скрывать нечего
     * (маркер снимается). Счётчики — то, что накопил хост: срез истории + подрезки.
     */
    fun hiddenMarkerText(hiddenMessages: Int, hiddenActivity: Int): String? {
        val parts = buildList {
            if (hiddenMessages > 0) add("${plural(hiddenMessages, "сообщение", "сообщения", "сообщений")}")
            if (hiddenActivity > 0) add(plural(hiddenActivity, "строка активности", "строки активности", "строк активности"))
        }
        if (parts.isEmpty()) return null
        return HIDDEN_PREFIX + parts.joinToString(" и ")
    }

    /** Русская плюрализация счётчика (1 сообщение / 2 сообщения / 5 сообщений). */
    private fun plural(count: Int, one: String, few: String, many: String): String {
        val form = when {
            count % 100 in 11..14 -> many
            count % 10 == 1 -> one
            count % 10 in 2..4 -> few
            else -> many
        }
        return "$count $form"
    }
}
