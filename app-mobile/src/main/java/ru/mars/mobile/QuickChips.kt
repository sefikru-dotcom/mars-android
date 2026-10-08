package ru.mars.mobile

/** Stable identity of a smart quick chip (icon mapping lives in the Activity). */
enum class QuickChipId { OK, CONTINUE, ROLLBACK, EXPLAIN, PLAN, STATUS }

/**
 * One smart quick-action chip above the composer: [label] is the chip caption,
 * [message] is the prompt sent to Марс on tap.
 */
data class QuickChip(val id: QuickChipId, val label: String, val message: String)

/**
 * Smart Quick Chips (WebUI parity): a context-aware ribbon of one-tap replies
 * above the composer. Pure data + visibility policy — JVM unit-testable.
 */
object QuickChips {
    val OK = QuickChip(QuickChipId.OK, "Ок", "Ок")
    val CONTINUE = QuickChip(QuickChipId.CONTINUE, "Продолжай", "Продолжай")
    val ROLLBACK = QuickChip(QuickChipId.ROLLBACK, "Откат", "Откати последнее изменение и верни предыдущее состояние")
    val EXPLAIN = QuickChip(QuickChipId.EXPLAIN, "Поясни", "Поясни подробнее своё последнее решение")
    val PLAN = QuickChip(QuickChipId.PLAN, "План", "Составь пошаговый план дальнейших действий")
    val STATUS = QuickChip(QuickChipId.STATUS, "Статус", "Краткий статус: что уже сделано и что сейчас в работе")

    val ALL: List<QuickChip> = listOf(OK, CONTINUE, ROLLBACK, EXPLAIN, PLAN, STATUS)

    /**
     * Smart visibility policy:
     * - no assistant reply yet → the ribbon is hidden (nothing to react to);
     * - while the stream is typing → hidden (the send button doubles as stop);
     * - after a failed turn → Откат and Поясни lead the row;
     * - otherwise → the full ribbon.
     */
    fun visibleChips(
        status: ChatStatus,
        hasAssistantReply: Boolean,
        lastTurnFailed: Boolean,
    ): List<QuickChip> = when {
        !hasAssistantReply -> emptyList()
        status == ChatStatus.TYPING -> emptyList()
        lastTurnFailed || status == ChatStatus.ERROR -> listOf(ROLLBACK, EXPLAIN, STATUS, CONTINUE)
        else -> ALL
    }
}
