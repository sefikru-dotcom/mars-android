package ru.mars.mobile

/** Как рисовать набор вызовов инструментов одного сообщения. */
enum class ToolGroupState {
    /** Группы нет: вызов один или их нет — карточки рисуются как раньше. */
    NONE,

    /** Группа свёрнута: одна строка-сводка, карточки НЕ создаются. */
    COLLAPSED,

    /** Группа развёрнута: строка-сводка плюс все карточки. */
    EXPANDED,
}

/**
 * Готовая модель группы инструментов одного сообщения.
 *
 * [cards] — ровно те вызовы, которые нужно построить. При [ToolGroupState.COLLAPSED]
 * список ПУСТ: Activity не должна ни инфлейтить карточки, ни прятать их через
 * `View.GONE` — плашку в дюжину карточек незачем даже создавать.
 */
data class ToolGroupModel(
    val state: ToolGroupState,
    /** Текст строки-сводки; пустая строка, когда группы нет. */
    val summary: String,
    /** Описание для TalkBack. */
    val summaryDescription: String,
    /** Строить ли строку-сводку. */
    val showsSummary: Boolean,
    /** Строить ли карточки вызовов. */
    val showsCards: Boolean,
    /** Иконка типа первого вызова (Activity мапит имя в res/drawable). */
    val iconToolName: String?,
    /** Шеврон свёрнутой группы указывает вниз, развёрнутой — вверх. */
    val chevronExpanded: Boolean,
    val cards: List<ToolExecution>,
)

/**
 * Решает, как показать вызовы инструментов одного сообщения (A16).
 *
 * Паритет с WebUI: там это `div.tool-call-group` со сводкой `tool-worklog-summary`,
 * классом `tool-call-group-collapsed` и ручным флагом разворота. В Android-клиенте
 * группы не было, поэтому ход с дюжиной вызовов рисовал дюжину плашек подряд.
 *
 * Правила:
 * - пусто или один вызов — группы НЕТ, карточка рисуется как раньше;
 * - два и больше — группа; начальное состояние берётся из режима активности:
 *   `STREAM_FULL` развёрнут, `STREAM_WINDOW`/`STREAM_QUIET` свёрнуты;
 * - ручное переключение пользователя ПЕРЕОПРЕДЕЛЯЕТ режим (как `data-mars-turn-manual`
 *   в WebUI): развернул вручную — остаётся развёрнутым, свёрнул — остаётся свёрнутым.
 *
 * Класс без Android-импортов: вся логика проверяется JVM-тестами.
 */
object ToolGroupPresenter {

    /** Порог группировки: от двух вызовов набор превращается в сворачиваемую группу. */
    const val GROUP_THRESHOLD = 2

    /**
     * @param tools вызовы инструментов одного сообщения;
     * @param mode текущий режим активности (даёт НАЧАЛЬНОЕ состояние);
     * @param manualExpanded ручное переопределение: `true` — развёрнуто, `false` —
     *   свёрнуто, `null` — пользователь ещё не трогал группу этого сообщения;
     * @param turnRunning идёт ли ещё ход — меняет только текст статуса в сводке.
     */
    fun model(
        tools: List<ToolExecution>,
        mode: ActivityDisplayMode = ActivityDisplayMode.DEFAULT,
        manualExpanded: Boolean? = null,
        turnRunning: Boolean = false,
    ): ToolGroupModel {
        if (tools.isEmpty()) {
            return ToolGroupModel(
                state = ToolGroupState.NONE,
                summary = "",
                summaryDescription = "",
                showsSummary = false,
                showsCards = false,
                iconToolName = null,
                chevronExpanded = false,
                cards = emptyList(),
            )
        }
        if (tools.size < GROUP_THRESHOLD) {
            return ToolGroupModel(
                state = ToolGroupState.NONE,
                summary = "",
                summaryDescription = "",
                showsSummary = false,
                showsCards = true,
                iconToolName = tools.first().name,
                chevronExpanded = true,
                cards = tools,
            )
        }

        val expanded = manualExpanded ?: (mode == ActivityDisplayMode.STREAM_FULL)
        val running = turnRunning || tools.any { it.result == null }
        val summary = summaryText(tools.size, running)
        return ToolGroupModel(
            state = if (expanded) ToolGroupState.EXPANDED else ToolGroupState.COLLAPSED,
            summary = summary,
            summaryDescription = summaryDescription(tools.size, running, expanded),
            showsSummary = true,
            showsCards = expanded,
            iconToolName = tools.first().name,
            chevronExpanded = expanded,
            cards = if (expanded) tools else emptyList(),
        )
    }

    /** Текст сводки: «6 инструментов · Готово» / «6 инструментов · выполняется». */
    fun summaryText(count: Int, running: Boolean): String =
        "$count ${plural(count)} · ${if (running) RUNNING_SUFFIX else DONE_SUFFIX}"

    /** Описание сводки для TalkBack: счёт, статус и что сделает тап. */
    fun summaryDescription(count: Int, running: Boolean, expanded: Boolean): String {
        val status = if (running) "выполняется" else "завершено"
        val action = if (expanded) "свернуть список" else "развернуть список"
        return "Вызовы инструментов: $count ${plural(count)}, $status. Нажмите, чтобы $action."
    }

    /**
     * Русская форма слова «инструмент»: 1 инструмент, 2-4 инструмента, 5+ инструментов.
     * Исключения 11-14 обязаны учитываться, иначе выходит «11 инструмент».
     */
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

    const val RUNNING_SUFFIX = "выполняется"
    const val DONE_SUFFIX = "Готово"
}
