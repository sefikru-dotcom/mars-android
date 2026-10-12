package ru.mars.mobile

import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.repository.InsightsResult

/**
 * Фаза вкладки Insights экрана мониторинга. Чистая JVM-модель — UI-слой
 * (InsightsActivity) только сопоставляет фазу с видимостью прогресса/списка/
 * статуса, все тексты и строки считает [InsightsPolicy].
 */
enum class InsightsPhase {
    /** Первая загрузка или смена периода: список может быть пуст или показывать прошлые строки. */
    LOADING,

    /** Снимок загружен, есть хотя бы одна строка. */
    READY,

    /** Снимок загружен, но за выбранный период данных нет. */
    EMPTY,

    /** Инсайты недоступны (сеть/сервер). Возможны offline-safe строки прошлого снимка. */
    ERROR,

    /** Нет сохранённой пары (credential): загрузка невозможна до входа. */
    NO_AUTH,
}

/** Тон числовой строки — определяет цвет значения (MarsTheme TEXT/STATUS_*). */
enum class StatTone { NEUTRAL, GOOD, WARN, BAD }

/**
 * Строка списка инсайтов. Все варианты — только отображение полей usage-снимка
 * [InsightsSnapshot]; read-only экран не порождает действий над данными.
 */
sealed interface InsightsRow {
    /** Заголовок секции («Итоги за период», «Модели», …). */
    data class Section(val title: String) : InsightsRow

    /** Счётчик/поле: подпись + значение + тон подсветки. */
    data class Stat(val label: String, val value: String, val tone: StatTone) : InsightsRow

    /** Сервисная заметка (например, «показаны первые N из M»). */
    data class Note(val text: String) : InsightsRow

    /** Строка `models[]`: имя модели, мета usage и форматированная стоимость. */
    data class ModelRow(val title: String, val meta: String, val costLabel: String) : InsightsRow

    /**
     * Строка-бар (`daily_tokens[]`, `activity_by_day[]`, `activity_by_hour[]`):
     * подпись, форматированное значение и [barWeight] — доля от максимума
     * секции (0f..1f), чистая арифметика без знания о сервере.
     */
    data class BarRow(val label: String, val value: String, val barWeight: Float) : InsightsRow
}

/**
 * Полное состояние вкладки Insights: фаза, выбранный период, строки,
 * внеполосное [notice] (например, «нет сети — показаны последние данные»),
 * центральный [message] для фаз без списка и флаг [canRetry].
 */
data class InsightsUiState(
    val phase: InsightsPhase,
    val periodDays: Int = InsightsPolicy.DEFAULT_DAYS,
    val rows: List<InsightsRow> = emptyList(),
    val notice: String? = null,
    val message: String? = null,
    val canRetry: Boolean = false,
)

/**
 * Чистая политика вкладки Insights (UI -> state/domain -> network): тексты,
 * фильтр периода и раскладка usage-снимка [InsightsSnapshot] в строки. Не знает
 * ни про Android, ни про сеть — данные приходят из
 * [ru.mars.network.repository.InsightsRepository] через [InsightsPresenter].
 *
 * Контракт `GET /api/insights` (проверен по deployed route_insights.py):
 * единственный параметр `days` сервер не валидирует с ошибкой, а клампит в
 * 1..365 (default 30 при отсутствии/мусоре) — веток 400/503 у маршрута нет.
 * Экран при этом предлагает только whitelisted чипы [PERIOD_DAYS], так что в
 * запрос попадают предсказуемые окна, а стартовый период совпадает с серверным
 * default ([DEFAULT_DAYS] = 30).
 */
object InsightsPolicy {

    /** Заголовок экрана мониторинга (обе вкладки). */
    const val SCREEN_TITLE = "Мониторинг"

    /** Whitelist чипов периода; всё вне списка нормализуется в [DEFAULT_DAYS]. */
    val PERIOD_DAYS: List<Int> = listOf(1, 7, 30, 90)

    /** Серверный default `days` (route_insights.py) — стартовый период экрана. */
    const val DEFAULT_DAYS = 30

    /** Потолок отрисовки `models[]` (сервер сортирует по стоимости — видны топ-модели). */
    const val MODEL_RENDER_LIMIT = 12

    /** Потолок отрисовки `daily_tokens[]`: показываются последние N дней окна. */
    const val DAILY_RENDER_LIMIT = 31

    const val MSG_LOADING = "Загрузка инсайтов…"
    const val MSG_EMPTY = "За выбранный период данных нет"
    const val MSG_ERROR = "Инсайты недоступны. Проверьте соединение и повторите."
    const val MSG_ERROR_OFFLINE = "Нет сети: инсайты недоступны"
    const val MSG_NO_AUTH = "Нужен вход: откройте приложение и пройдите пару"
    const val NOTICE_OFFLINE = "Нет сети"
    const val NOTICE_OFFLINE_STALE = "Нет сети — показаны последние данные"
    const val RETRY_LABEL = "Повторить"

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
        String.format(java.util.Locale.US, "%,d", value).replace(",", " ")

    /**
     * Стоимость в долларах: сервер отдаёт float с округлением до 6 знаков —
     * показываем 2 знака для сумм >= $1 и до 6 значащих для мелких, лишние нули
     * убираются. Ноль/мусор/отрицательное -> `$0` (сервер clamp-ит cost >= 0).
     */
    fun formatCost(cost: Double): String {
        if (!cost.isFinite() || cost <= 0.0) return "\$0"
        val decimals = if (cost >= 1.0) 2 else 6
        var text = String.format(java.util.Locale.US, "%.${decimals}f", cost)
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

    /** Час суток -> подпись бара: `4` -> `04:00`. */
    fun hourLabel(hour: Int): String = String.format(java.util.Locale.US, "%02d:00", hour)

    /**
     * Пустой usage-снимок: все серверные счётчики нулевые — READY вырождается в
     * EMPTY. Проверяются именно totals (`total_sessions`, `total_messages`,
     * `total_tokens`, `total_cost`), потому что `daily_tokens`/`activity_by_*`
     * сервер zero-fill'ит и они непустые даже за период без активности.
     */
    fun isEmpty(snapshot: InsightsSnapshot): Boolean =
        snapshot.totalSessions <= 0 &&
            snapshot.totalMessages <= 0 &&
            snapshot.totalTokens <= 0L &&
            snapshot.totalCacheReadTokens <= 0L &&
            snapshot.totalCost <= 0.0

    /** Раскладка снимка в строки экрана (стабильный порядок секций). */
    fun rows(snapshot: InsightsSnapshot): List<InsightsRow> {
        val out = mutableListOf<InsightsRow>()
        out += summaryRows(snapshot)
        out += modelRows(snapshot)
        out += dailyTokenRows(snapshot)
        out += weekdayRows(snapshot)
        out += hourRows(snapshot)
        return out
    }

    private fun summaryRows(snapshot: InsightsSnapshot): List<InsightsRow> {
        val out = mutableListOf<InsightsRow>()
        out += InsightsRow.Section("Итоги за период")
        if (snapshot.periodDays > 0) {
            out += InsightsRow.Stat("Период", daysLabel(snapshot.periodDays), StatTone.NEUTRAL)
        }
        out += InsightsRow.Stat("Сессии", formatCount(snapshot.totalSessions.toLong()), StatTone.NEUTRAL)
        out += InsightsRow.Stat("Сообщения", formatCount(snapshot.totalMessages.toLong()), StatTone.NEUTRAL)
        out += InsightsRow.Stat("Токены", formatCount(snapshot.totalTokens), StatTone.NEUTRAL)
        out += InsightsRow.Stat("Входные токены", formatCount(snapshot.totalInputTokens), StatTone.NEUTRAL)
        out += InsightsRow.Stat("Выходные токены", formatCount(snapshot.totalOutputTokens), StatTone.NEUTRAL)
        if (snapshot.totalCacheReadTokens > 0) {
            out += InsightsRow.Stat("Кеш-прочтения", formatCount(snapshot.totalCacheReadTokens), StatTone.NEUTRAL)
        }
        snapshot.totalCacheHitPercent?.let {
            out += InsightsRow.Stat("Попадание в кеш", "$it%", StatTone.GOOD)
        }
        out += InsightsRow.Stat(
            "Стоимость",
            formatCost(snapshot.totalCost),
            if (snapshot.totalCost > 0.0) StatTone.WARN else StatTone.NEUTRAL,
        )
        return out
    }

    private fun modelRows(snapshot: InsightsSnapshot): List<InsightsRow> {
        if (snapshot.models.isEmpty()) return emptyList()
        val out = mutableListOf<InsightsRow>()
        out += InsightsRow.Section("Модели")
        snapshot.models.take(MODEL_RENDER_LIMIT).forEach { model ->
            val meta = listOfNotNull(
                "${formatCount(model.sessions.toLong())} ${plural(model.sessions, "сессия", "сессии", "сессий")}",
                "${formatCount(model.totalTokens)} токенов",
                model.cacheHitPercent?.let { "кеш $it%" },
            ).joinToString(" · ")
            out += InsightsRow.ModelRow(
                title = model.model.ifBlank { "unknown" },
                meta = meta,
                costLabel = formatCost(model.cost),
            )
        }
        if (snapshot.models.size > MODEL_RENDER_LIMIT) {
            out += InsightsRow.Note("Показаны первые $MODEL_RENDER_LIMIT из ${snapshot.models.size}")
        }
        return out
    }

    private fun dailyTokenRows(snapshot: InsightsSnapshot): List<InsightsRow> {
        if (snapshot.dailyTokens.isEmpty()) return emptyList()
        val shown = snapshot.dailyTokens.takeLast(DAILY_RENDER_LIMIT)
        val max = shown.maxOf { it.totalTokens }
        if (max <= 0L) return emptyList() // серверный zero-fill: рисовать нечего
        val out = mutableListOf<InsightsRow>()
        out += InsightsRow.Section("Токены по дням")
        shown.forEach { day ->
            out += InsightsRow.BarRow(
                label = shortDate(day.date),
                value = formatCount(day.totalTokens),
                barWeight = weightOf(day.totalTokens, max),
            )
        }
        if (snapshot.dailyTokens.size > shown.size) {
            out += InsightsRow.Note(
                "Показаны последние ${shown.size} из ${snapshot.dailyTokens.size} " +
                    plural(snapshot.dailyTokens.size, "дня", "дней", "дней")
            )
        }
        return out
    }

    private fun weekdayRows(snapshot: InsightsSnapshot): List<InsightsRow> {
        if (snapshot.activityByDay.isEmpty()) return emptyList()
        val max = snapshot.activityByDay.maxOf { it.sessions }
        if (max <= 0) return emptyList()
        val out = mutableListOf<InsightsRow>()
        out += InsightsRow.Section("Активность по дням недели")
        snapshot.activityByDay.forEach { day ->
            out += InsightsRow.BarRow(
                label = weekdayLabel(day.day),
                value = formatCount(day.sessions.toLong()),
                barWeight = weightOf(day.sessions.toLong(), max.toLong()),
            )
        }
        return out
    }

    private fun hourRows(snapshot: InsightsSnapshot): List<InsightsRow> {
        if (snapshot.activityByHour.isEmpty()) return emptyList()
        val max = snapshot.activityByHour.maxOf { it.sessions }
        if (max <= 0) return emptyList()
        val out = mutableListOf<InsightsRow>()
        out += InsightsRow.Section("Активность по часам")
        snapshot.activityByHour.forEach { hour ->
            out += InsightsRow.BarRow(
                label = hourLabel(hour.hour),
                value = formatCount(hour.sessions.toLong()),
                barWeight = weightOf(hour.sessions.toLong(), max.toLong()),
            )
        }
        return out
    }

    /** Доля [value] от [max] в 0f..1f (вес бара); пустая секция -> 0f. */
    fun weightOf(value: Long, max: Long): Float =
        if (max > 0L) (value.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f

    /**
     * Текст ошибки для неуспешного [InsightsResult]: у маршрута нет валидационных
     * веток — любой сбой (auth-guard 401/403, неожиданный 500, транспорт) даёт
     * общий текст; серверная деталь остаётся в `Failed.message` для логов.
     * Для [InsightsResult.Snapshot] возвращает null.
     */
    fun errorMessage(result: InsightsResult): String? = when (result) {
        is InsightsResult.Snapshot -> null
        is InsightsResult.Failed -> MSG_ERROR
    }
}
