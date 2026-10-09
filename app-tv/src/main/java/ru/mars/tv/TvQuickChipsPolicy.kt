package ru.mars.tv

/**
 * One quick chip of the TV chat composer row: [label] is what the D-pad-focusable
 * button shows on screen, [text] is the message body sent when the chip activates
 * (a chip fires a full turn, so its text may be longer than the label).
 */
data class QuickChip(val id: String, val label: String, val text: String)

/**
 * Pure policy of the TV chat's quick chips (no Android, no I/O — JVM-testable,
 * [TvPlaylistPolicy]/[TvSessionBrowserPolicy] parity; WebUI quick-chip control
 * parity). The Activity owns the widgets and asks this object for every decision:
 *
 * - [chips]: the fixed row content in D-pad order (left to right).
 * - [chipsEnabled]: activation rule — a chip is a full turn, so the row is live
 *   only while the composer is live (input not blocked and no answer streaming).
 * - [move]: clamped LEFT/RIGHT stepping inside the row (no wrap).
 * - [byId]: chip lookup for activation.
 */
object TvQuickChipsPolicy {

    /** No focused chip (empty row / nothing selected yet). */
    const val NO_SELECTION = -1

    /** The quick-chip row, in D-pad order (left to right). */
    val chips: List<QuickChip> = listOf(
        QuickChip("ok", "Ок", "Ок"),
        QuickChip("continue", "Продолжай", "Продолжай"),
        QuickChip("rollback", "Откат", "Откат"),
        QuickChip("explain", "Поясни", "Поясни"),
        QuickChip("finish", "Финиш", "Ок, закрываю чат"),
    )

    /**
     * True while the chips may be activated: the composer input is not blocked and
     * no answer stream is in flight — the same honesty rule as the send button
     * (no second turn is started while one is running).
     */
    fun chipsEnabled(streamActive: Boolean, inputBlocked: Boolean): Boolean =
        !streamActive && !inputBlocked

    /**
     * D-pad LEFT/RIGHT stepping ([TvSessionBrowserPolicy.move] parity): clamped at
     * the row edges (never wraps), an out-of-range [selection] snaps to the edge the
     * [delta] points at; [NO_SELECTION] for an empty row.
     */
    fun move(selection: Int, size: Int, delta: Int): Int {
        if (size <= 0) return NO_SELECTION
        if (selection !in 0 until size) return if (delta > 0) 0 else size - 1
        return (selection + delta).coerceIn(0, size - 1)
    }

    /** The chip with [id], or `null` when the row has no such chip. */
    fun byId(id: String): QuickChip? = chips.firstOrNull { it.id == id }
}
