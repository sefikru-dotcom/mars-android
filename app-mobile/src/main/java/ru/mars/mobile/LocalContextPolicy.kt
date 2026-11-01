package ru.mars.mobile

import ru.mars.network.local.LocalUsage

/**
 * Уровень заполнения окна контекста — определяет цвет пилюли в композере.
 *
 * Пороги те же, что у серверного [ContextBudgetLevel] и кольца WebUI: зелёный до 50%,
 * оранжевый до 75%, красный выше. Два набора порогов разъехались бы — и одна и та же
 * цифра красилась бы по-разному в серверном и локальном чате.
 */
enum class LocalContextLevel { NORMAL, WARNING, CRITICAL }

/**
 * Презентация пилюли контекста локального диалога: текст, уровень, озвучка, процент.
 *
 * [visible] == false означает «показывать нечего»: провайдер не прислал `usage` или
 * размер окна модели неизвестен. Нулевой процент в этом случае НЕ рисуется — фейковый
 * «0%» читался бы как «контекст пуст», хотя на самом деле он просто не измерен
 * (провайдер отдаёт `usage` не всегда, см. §2 контракта волны 2).
 *
 * Неизменяемый снимок: экран только рисует, политика ничего не хранит.
 */
data class LocalContextView(
    val visible: Boolean,
    /** Текст пилюли, например «12.4k / 200k · 6%»; null, когда пилюля скрыта. */
    val text: String? = null,
    val level: LocalContextLevel? = null,
    /** Описание для скринридера по-русски; null, когда пилюля скрыта. */
    val contentDescription: String? = null,
    /** Процент заполнения окна, клампится к 100 (дуга кольца). */
    val percent: Int? = null,
    /** Токены запроса последнего шага — вся отправленная история на этом шаге. */
    val usedTokens: Int = 0,
    /** Размер окна контекста модели; null — провайдер его не сообщил. */
    val windowTokens: Int? = null,
)

/**
 * Чистая политика заполнения контекстного окна локального чата (паритет с серверным
 * [ContextBudgetPolicy]).
 *
 * Источник замера — [LocalUsage] из кадра `usage` провайдера. Для заполнения окна берётся
 * `promptTokens` ПОСЛЕДНЕГО шага: он уже включает всю отправленную историю, поэтому сумма
 * по шагам удвоила бы процент (см. документацию [LocalUsage]). Кумулятивный расход
 * диалога — отдельная величина, её показывает [SessionUsagePolicy]-подобная подпись, а не
 * эта политика.
 *
 * Ни одного импорта `android.*`: формат чисел — [formatTokenCount], поэтому политика
 * покрывается JVM unit-тестами без эмулятора.
 */
object LocalContextPolicy {

    /** Порог предупреждения: больше 50% — оранжевый (как в [ContextBudgetPolicy]). */
    const val WARNING_PERCENT = 50

    /** Порог опасности: больше 75% — красный (как в [ContextBudgetPolicy]). */
    const val CRITICAL_PERCENT = 75

    /** Детали, когда провайдер ещё не прислал `usage`. */
    const val UNKNOWN_DETAILS =
        "Заполнение контекста ещё не измерено — провайдер не сообщил расход."

    /** Детали, когда расход есть, а размер окна модели неизвестен. */
    const val WINDOW_UNKNOWN_DETAILS =
        "Размер окна контекста неизвестен — процент не считается."

    /**
     * Уровень по проценту. Границы строгие (`>`), как на сервере: ровно 50% — ещё
     * зелёный, ровно 75% — ещё оранжевый.
     */
    fun level(percent: Int): LocalContextLevel = when {
        percent > CRITICAL_PERCENT -> LocalContextLevel.CRITICAL
        percent > WARNING_PERCENT -> LocalContextLevel.WARNING
        else -> LocalContextLevel.NORMAL
    }

    /**
     * Процент заполнения окна: `round(used / window * 100)`, клампится к 100.
     *
     * null, когда окно неизвестно или неположительно, либо замер нулевой: подставлять
     * «0%» вместо неизвестного значения нельзя — это ложь, а не заглушка.
     */
    fun percent(usedTokens: Int, windowTokens: Int?): Int? {
        if (windowTokens == null || windowTokens <= 0) return null
        if (usedTokens <= 0) return null
        val raw = Math.round(usedTokens.toLong() * 100.0 / windowTokens).toInt()
        return raw.coerceIn(0, 100)
    }

    /**
     * Состояние пилюли по замеру последнего шага.
     *
     * Скрыта, если окно неизвестно/неположительно или замер нулевой. Иначе — текст
     * «12.4k / 200k · 6%», уровень по [level] и описание для скринридера.
     */
    fun view(promptTokens: Int, windowTokens: Int?): LocalContextView {
        val percent = percent(promptTokens, windowTokens) ?: return hidden()
        val window = windowTokens ?: return hidden()
        val level = level(percent)
        return LocalContextView(
            visible = true,
            text = pillText(promptTokens, window, percent),
            level = level,
            contentDescription = contentDescription(percent, level, promptTokens, window),
            percent = percent,
            usedTokens = promptTokens,
            windowTokens = window,
        )
    }

    /** Скрытая пилюля — единственное представление «показывать нечего». */
    fun hidden(): LocalContextView = LocalContextView(visible = false)

    /** Текст пилюли: «12.4k / 200k · 6%». Хвостовой «.0» у круглых чисел срезается. */
    fun pillText(usedTokens: Int, windowTokens: Int, percent: Int): String =
        "${formatTokens(usedTokens)} / ${formatTokens(windowTokens)} · $percent%"

    /**
     * Описание пилюли для скринридера. Уровень озвучивается словами, а не цветом:
     * цвет скринридеру недоступен, а «близко к пределу» — смысл, ради которого пилюля
     * и существует.
     */
    fun contentDescription(
        percent: Int,
        level: LocalContextLevel,
        usedTokens: Int,
        windowTokens: Int,
    ): String = buildString {
        append("Заполнение контекста: $percent%")
        append(", ${formatTokens(usedTokens)} из ${formatTokens(windowTokens)} токенов")
        when (level) {
            LocalContextLevel.NORMAL -> Unit
            LocalContextLevel.WARNING -> append(". Контекст заполнен больше половины")
            LocalContextLevel.CRITICAL -> append(". Контекст почти заполнен, скоро потребуется новый диалог")
        }
    }

    /**
     * Текст детального диалога: заполнение, разбивка расхода и пороги в токенах.
     *
     * При неизвестном окне честно говорит, что процент не считается, но разбивку
     * расхода всё равно показывает — она от окна не зависит.
     */
    fun detailsText(usage: LocalUsage, windowTokens: Int?): String {
        if (!usage.isMeasured) return UNKNOWN_DETAILS
        val window = windowTokens?.takeIf { it > 0 }
        val percent = percent(usage.promptTokens, window)
        return buildString {
            if (window == null || percent == null) {
                append(WINDOW_UNKNOWN_DETAILS)
            } else {
                append("Заполнено: $percent% (${formatTokens(usage.promptTokens)} из ${formatTokens(window)})")
                append("\nПредупреждение при: $WARNING_PERCENT% · ${formatTokens(thresholdTokens(window, WARNING_PERCENT))}")
                append("\nОпасность при: $CRITICAL_PERCENT% · ${formatTokens(thresholdTokens(window, CRITICAL_PERCENT))}")
            }
            append("\nЗапрос (последний шаг): ${formatTokens(usage.promptTokens)}")
            append("\nОтвет: ${formatTokens(usage.completionTokens)}")
            append("\nВсего за диалог: ${formatTokens(usage.totalTokens)}")
            if (window != null) append("\nРазмер окна: ${formatTokens(window)}")
        }
    }

    /** Токены порога: `round(window * percent / 100)`. */
    fun thresholdTokens(windowTokens: Int, percent: Int): Int =
        Math.round(windowTokens.toLong() * percent / 100.0).toInt()

    /**
     * Компактное число токенов в стиле WebUI (`12.4k`, `1.5M`), но без хвостового
     * «.0»: в пилюле ждут «200k», а не «200.0k». Второго форматтера не заводим —
     * округление и единицы берутся из [formatTokenCount].
     */
    fun formatTokens(tokens: Int): String = formatTokenCount(tokens)
        .replace(".0k", "k")
        .replace(".0M", "M")
}
