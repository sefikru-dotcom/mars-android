package ru.mars.tv.insights

import ru.mars.network.model.InsightsSnapshot
import java.util.Locale

/**
 * Одна отрисованная строка вкладки «Аналитика» — sealed-иерархия
 * ([ru.mars.tv.insights.TvInsightRow]), Activity лишь сопоставляет тип строки
 * с внешним видом. Политика остаётся чисто JVM: без Compose/View/ресурсов.
 */
sealed interface TvInsightRow {
    /** Заголовок раздела; [TvInsightRow.Section.subtitle] — необязательная подпись. */
    data class Section(val title: String, val subtitle: String? = null) : TvInsightRow

    /** Пара «Показатель — Значение» (числа/стоимости/проценты). */
    data class Stat(val label: String, val value: String) : TvInsightRow

    /** Строка модели: имя, мета (доли/кэш) и стоимость справа. */
    data class ModelRow(val title: String, val meta: String?, val cost: String) : TvInsightRow

    /** Горизонтальный бар: подпись, значение и доля 0..1 от максимума ряда. */
    data class BarRow(val label: String, val value: String, val percentOfMax: Float) : TvInsightRow

    /** Поясняющая строка (пустой снимок, отсутствие моделей). */
    data class Note(val text: String) : TvInsightRow
}

/**
 * Pure presentation policy of the TV «Аналитика» tab (no Android, no I/O —
 * JVM-testable; wording is mobile `InsightsPolicy` parity: те же чипы периодов
 * 1/7/30/90, форматтеры счётчиков/стоимости/дат, русские ярлыки дней недели и
 * часов, честные пустые состояния). Экран СТРОГО READ-ONLY: единственный
 * сетевой вызов — `GET /api/insights` (read-only белый список нативного токена).
 *
 * Все числа/стоимости/проценты/ряды берутся из снимка как есть; отсутствующие
 * показатели показываются как «—», нулевые ряды не выдумываются.
 */
object TvInsightsPolicy {

    /** [restoreSelection] result when there is nothing to focus. */
    const val NO_SELECTION = -1

    /** Допустимые периоды (whitelist сервера: 1..365, UI не выходит за набор). */
    val PERIOD_DAYS = listOf(1, 7, 30, 90)

    /** Период по умолчанию — мобильный/серверный паритет. */
    const val DEFAULT_DAYS = 30

    const val TITLE = "Аналитика"

    const val SECTION_PERIOD = "Период"
    const val SECTION_USAGE = "Использование"
    const val SECTION_TOKENS = "Токены"
    const val SECTION_MODELS = "Модели"
    const val SECTION_DAILY = "Токены по дням"
    const val SECTION_WEEKDAY = "Активность по дням недели"
    const val SECTION_HOURLY = "Активность по часам"

    const val LABEL_DAYS = "Дней в окне"
    const val LABEL_SESSIONS = "Сессий"
    const val LABEL_MESSAGES = "Сообщений"
    const val LABEL_COST = "Стоимость"
    const val LABEL_CACHE_HIT = "Попадание в кэш"
    const val LABEL_INPUT = "Входные"
    const val LABEL_OUTPUT = "Выходные"
    const val LABEL_CACHE_READ = "Чтение кэша"
    const val LABEL_TOTAL = "Всего"

    const val EMPTY_SNAPSHOT = "Нет данных за период"
    const val NO_MODELS = "Модели не использовались"
    const val DASH = "—"

    /** Максимальный возраст снимка аналитики — агрегаты живут дольше операционных данных (24 ч). */
    const val CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Нормализация периода: только whitelisted значения, иначе [DEFAULT_DAYS]. */
    fun sanitizePeriod(days: Int): Int = if (PERIOD_DAYS.contains(days)) days else DEFAULT_DAYS

    /** Подпись чипа периода: корректное русское склонение «день/дня/дней». */
    fun daysLabel(days: Int): String = "$days ${pluralDays(days)}"

    fun pluralDays(days: Int): String = plural(days, "день", "дня", "дней")

    /** Русское склонение существительного при числе [n] (one/few/many). */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val mod10 = kotlin.math.abs(n) % 10
        val mod100 = kotlin.math.abs(n) % 100
        return when {
            mod10 == 1 && mod100 != 11 -> one
            mod10 in 2..4 && mod100 !in 12..14 -> few
            else -> many
        }
    }

    /** Счётчик с группировкой разрядов неразрывным пробелом: `902 110`. */
    fun formatCount(value: Long): String =
        String.format(Locale.US, "%,d", value).replace(",", " ")

    /**
     * Стоимость в долларах: 2 знака для сумм >= $1, до 6 значащих для мелких,
     * лишние нули убираются; ноль/мусор/отрицательное -> `$0` — mobile parity.
     */
    fun formatCost(cost: Double): String {
        if (!cost.isFinite() || cost <= 0.0) return "\$0"
        val decimals = if (cost >= 1.0) 2 else 6
        var text = String.format(Locale.US, "%.${decimals}f", cost)
        if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
        return "\$$text"
    }

    /** `2026-10-12` -> `12.10`; любой другой формат возвращается как есть. */
    fun shortDate(date: String): String {
        val parts = date.split('-')
        return if (parts.size == 3 && parts[0].length == 4) "${parts[2]}.${parts[1]}" else date
    }

    /** Серверные ярлыки дней недели (`Mon`..`Sun`) -> русские; неизвестный — как есть. */
    fun weekdayLabel(day: String): String = when (day) {
        "Mon" -> "Пн"
        "Tue" -> "Вт"
        "Wed" -> "Ср"
        "Thu" -> "Чт"
        "Fri" -> "Пт"
        "Sat" -> "Сб"
        "Sun" -> "Вс"
        else -> day
    }

    /** Часовой бакет 0..23 -> «00:00»..«23:00»; вне диапазона — как пришёл. */
    fun hourLabel(hour: Int): String =
        if (hour in 0..23) String.format(Locale.US, "%02d:00", hour) else "$hour:00"

    /** Пустой снимок: сервер не отдал ни сессий, ни сообщений, ни токенов за окно. */
    fun isEmpty(snapshot: InsightsSnapshot): Boolean =
        snapshot.totalSessions == 0 && snapshot.totalMessages == 0 && snapshot.totalTokens == 0L

    /**
     * Полная раскладка снимка в строки вкладки: период/использование/токены —
     * константные секции (значение «—» когда фактов нет), модели и ряды
     * активности — только при непустых данных (нулевые бары не выдумываются).
     */
    fun rows(snapshot: InsightsSnapshot): List<TvInsightRow> {
        if (isEmpty(snapshot)) {
            return listOf(
                TvInsightRow.Section(SECTION_PERIOD, daysLabel(snapshot.periodDays)),
                TvInsightRow.Note(EMPTY_SNAPSHOT),
            )
        }
        return buildList {
            add(TvInsightRow.Section(SECTION_PERIOD, daysLabel(snapshot.periodDays)))
            add(TvInsightRow.Section(SECTION_USAGE))
            add(TvInsightRow.Stat(LABEL_SESSIONS, formatCount(snapshot.totalSessions.toLong())))
            add(TvInsightRow.Stat(LABEL_MESSAGES, formatCount(snapshot.totalMessages.toLong())))
            add(TvInsightRow.Stat(LABEL_COST, formatCost(snapshot.totalCost)))
            add(
                TvInsightRow.Stat(
                    LABEL_CACHE_HIT,
                    snapshot.totalCacheHitPercent?.let { "$it%" } ?: DASH,
                ),
            )
            add(TvInsightRow.Section(SECTION_TOKENS))
            add(TvInsightRow.Stat(LABEL_INPUT, formatCount(snapshot.totalInputTokens)))
            add(TvInsightRow.Stat(LABEL_OUTPUT, formatCount(snapshot.totalOutputTokens)))
            add(TvInsightRow.Stat(LABEL_CACHE_READ, formatCount(snapshot.totalCacheReadTokens)))
            add(TvInsightRow.Stat(LABEL_TOTAL, formatCount(snapshot.totalTokens)))

            add(TvInsightRow.Section(SECTION_MODELS, "${plural(snapshot.models.size, "модель", "модели", "моделей")}"))
            if (snapshot.models.isEmpty()) {
                add(TvInsightRow.Note(NO_MODELS))
            } else {
                snapshot.models.forEach { model ->
                    add(
                        TvInsightRow.ModelRow(
                            title = model.model.ifBlank { DASH },
                            meta = modelMeta(model.sessions, model.tokenShare, model.costShare, model.cacheHitPercent),
                            cost = formatCost(model.cost),
                        ),
                    )
                }
            }

            barSection(
                this,
                SECTION_DAILY,
                snapshot.dailyTokens.map { Triple(shortDate(it.date), it.totalTokens, it.sessions) },
            ) { value, sessions ->
                "${formatCount(value)} ток. · ${plural(sessions, "сессия", "сессии", "сессий")}"
            }
            barSection(
                this,
                SECTION_WEEKDAY,
                snapshot.activityByDay.map { Triple(weekdayLabel(it.day), it.sessions.toLong(), it.sessions) },
            ) { _, sessions -> "${plural(sessions, "сессия", "сессии", "сессий")}: $sessions" }
            barSection(
                this,
                SECTION_HOURLY,
                snapshot.activityByHour.map { Triple(hourLabel(it.hour), it.sessions.toLong(), it.sessions) },
            ) { _, sessions -> "${plural(sessions, "сессия", "сессии", "сессий")}: $sessions" }
        }
    }

    /** Мета строки модели: сессии, доли токенов/расходов, кэш — mobile parity, «—» при null. */
    private fun modelMeta(sessions: Int, tokenShare: Int, costShare: Int, cacheHitPercent: Int?): String =
        listOf(
            "${plural(sessions, "сессия", "сессии", "сессий")}: $sessions",
            "токены: $tokenShare%",
            "расходы: $costShare%",
            "кэш: ${cacheHitPercent?.let { "$it%" } ?: DASH}",
        ).joinToString(" · ")

    /**
     * Секция баров: заголовок + ряды с долей от максимума; полностью нулевой ряд
     * не рендерится (пустые бакеты — шум на 10-футовом экране). [render] строит
     * подпись значения из числа и счётчика сессий.
     */
    private fun barSection(
        sink: MutableList<TvInsightRow>,
        title: String,
        entries: List<Triple<String, Long, Int>>,
        render: (value: Long, sessions: Int) -> String,
    ) {
        val maxValue = entries.maxOfOrNull { it.second } ?: 0L
        if (maxValue <= 0L) return
        sink.add(TvInsightRow.Section(title))
        entries.forEach { (label, value, sessions) ->
            if (value <= 0L) return@forEach
            sink.add(
                TvInsightRow.BarRow(
                    label = label,
                    value = render(value, sessions),
                    percentOfMax = (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f),
                ),
            )
        }
    }

    /**
     * Focus restoration after a re-render (period switch, auto-refresh): the
     * previous index clamped into the new list — edges STAY, never wrap;
     * [NO_SELECTION] when the list is empty.
     */
    fun restoreSelection(previous: Int, size: Int): Int =
        if (size <= 0) NO_SELECTION else previous.coerceIn(0, size - 1)
}
