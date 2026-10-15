package ru.mars.mobile

import ru.mars.network.model.SessionUsage

/**
 * Презентация расхода сессии: текст компактной пилюли, её видимость и строки диалога.
 * [visible] == false означает «показывать нечего» — нулевой или недоступный расход
 * не превращается в фейковые нули в интерфейсе.
 */
data class SessionUsageView(
    val visible: Boolean,
    val text: String?,
    val contentDescription: String?,
)

/**
 * Чистая политика расхода/стоимости сессии (A14, паритет с счётчиком WebUI).
 *
 * Источник данных — `GET /api/session/usage` ([ru.mars.network.model.SessionUsage]),
 * а НЕ metering-кадры потока: пилюля контекста ([ContextBudgetPolicy]) считает
 * заполнение окна, эта — кумулятивный расход и деньги. Два источника намеренно
 * не смешиваются, иначе неизвестный расход «наследовал» бы проценты контекста.
 *
 * Правила видимости: пилюля появляется, только когда сервер реально сообщил
 * непустые токены ИЛИ положительную стоимость; отсутствие цены не делает
 * сессию «бесплатной» — стоимость просто не показывается. Без Android-импортов
 * (формат чисел — [formatTokenCount]) — покрывается JVM unit-тестами.
 */
object SessionUsagePolicy {

    /** Детали, когда расход ещё не измерен или сервер его не отдал. */
    const val UNAVAILABLE_DETAILS = "Расход сессии ещё не измерен или недоступен."

    /** Метка недоступной стоимости: подставлять «$0.00» вместо неё нельзя (это ложь). */
    const val COST_UNAVAILABLE = "не сообщается провайдером"

    /** Состояние пилюли расхода: скрыта при нулевом/недоступном расходе. */
    fun view(usage: SessionUsage?): SessionUsageView {
        if (usage == null) return SessionUsageView(false, null, null)
        val tokens = usage.totalTokens.takeIf { it > 0 }
        val cost = formatCost(usage.cost)
        if (tokens == null && cost == null) return SessionUsageView(false, null, null)
        val text = listOfNotNull(
            tokens?.let { "${formatTokenCount(it)} ток" },
            cost,
        ).joinToString(" · ")
        val spoken = buildString {
            append("Расход сессии: ")
            append(tokens?.let { "${formatTokenCount(it)} токенов" } ?: "токены не сообщаются")
            if (cost != null) append(", стоимость $cost")
        }
        return SessionUsageView(visible = true, text = text, contentDescription = spoken)
    }

    /** Текст детального диалога: разбивка токенов и стоимость. */
    fun detailsText(usage: SessionUsage?): String {
        if (usage == null) return UNAVAILABLE_DETAILS
        val cost = formatCost(usage.cost)
        if (!usage.hasTokens && cost == null) return UNAVAILABLE_DETAILS
        return buildString {
            if (usage.hasTokens) {
                append("Токены: ${formatTokenCount(usage.totalTokens)}")
                append("\nЗапрос: ${formatTokenCount(usage.promptTokens)}")
                append("\nОтвет: ${formatTokenCount(usage.completionTokens)}")
            } else {
                append("Токены: не сообщаются")
            }
            append("\nСтоимость: ")
            append(cost ?: COST_UNAVAILABLE)
        }
    }

    /**
     * Стоимость в виде `$0.42`. `null` при отсутствии цены, `0.0` или отрицательном
     * значении: нулевая цена — не «бесплатно», а «неизвестно» (сервер отвечает
     * `null`, когда у провайдера нет прайса). Копеечные суммы показываются с
     * четырьмя знаками, чтобы `$0.0003` не превратился в `$0.00`.
     */
    fun formatCost(cost: Double?): String? {
        if (cost == null || cost <= 0.0 || cost.isNaN() || cost.isInfinite()) return null
        val pattern = if (cost < 0.01) "%.4f" else "%.2f"
        return "$" + String.format(java.util.Locale.US, pattern, cost)
    }
}
