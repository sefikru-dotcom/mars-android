package ru.mars.mobile

/**
 * Presentation model of the «Мысли Марса» accordion, consumed by the (thin)
 * Android render layer. Pure data — no Android imports, JVM unit-testable.
 */
data class ThinkingCardView(
    /** False when the message carries no thoughts at all — the card is not rendered. */
    val visible: Boolean,
    /** Chip text on the card header («Размышления Марса»). */
    val chipLabel: String,
    /**
     * Status indicator next to the chip: «Думает…» while the turn is streaming,
     * otherwise the fragment-count summary («3 фрагмента»). Null only for an
     * invisible card.
     */
    val statusLabel: String?,
    /** True while the turn is still streaming thoughts (pulse indicator on the plate). */
    val streaming: Boolean,
    /** True → render ic_chevron_up, false → ic_chevron_down (icons via R.drawable, never glyphs). */
    val chevronUp: Boolean,
    val expanded: Boolean,
    /** Normalized thought text — rendered italic with a [bodyIndentDp] start indent. */
    val bodyText: String,
    /** Layout indent for the italic body (special thought-block offset, dp). */
    val bodyIndentDp: Int,
    /** Number of non-empty thought fragments. */
    val fragmentCount: Int,
    /** Label of the copy action («Скопировать мысли»). */
    val copyLabel: String,
    /** Clipboard payload — always the full normalized text. */
    val copySource: String,
    // Palette tokens (MarsTheme constants, inlined at compile time — no Android class is loaded).
    val chipColor: Int,
    val bodyColor: Int,
    val cardColor: Int,
    val borderColor: Int,
) {
    companion object {
        /** Shared invisible instance — nothing to render. */
        val HIDDEN = ThinkingCardView(
            visible = false,
            chipLabel = ThinkingCardPresenter.CHIP_LABEL,
            statusLabel = null,
            streaming = false,
            chevronUp = false,
            expanded = false,
            bodyText = "",
            bodyIndentDp = ThinkingCardPresenter.BODY_INDENT_DP,
            fragmentCount = 0,
            copyLabel = ThinkingCardPresenter.COPY_LABEL,
            copySource = "",
            chipColor = MarsTheme.ACCENT_TEXT,
            bodyColor = MarsTheme.TEXT_EM,
            cardColor = MarsTheme.THOUGHT_BG,
            borderColor = MarsTheme.THOUGHT_BORDER,
        )
    }
}

/**
 * Pure presentation logic for the thoughts accordion (WebUI parity):
 * chip header, live «Думает…» indicator while streaming, fragment summary when
 * settled, italic body normalization and the copy source.
 * No Android imports — JVM unit-testable.
 */
object ThinkingCardPresenter {
    const val CHIP_LABEL = "Размышления Марса"
    const val THINKING_LABEL = "Думает…"
    const val COPY_LABEL = "Скопировать мысли"
    /** Special start indent of the italic thought body (dp) — applied by the render layer. */
    const val BODY_INDENT_DP = 12

    /** Drops blank fragments and trims the rest. */
    fun normalize(thoughts: List<String>): List<String> =
        thoughts.map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Body text: fragments joined with a newline; runs of 3+ newlines collapse to a
     * paragraph break so streamed deltas never turn into a ladder of empty lines.
     */
    fun bodyText(thoughts: List<String>): String =
        normalize(thoughts).joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    fun fragmentCount(thoughts: List<String>): Int = normalize(thoughts).size

    /** Russian plural for «фрагмент» (thought stream chunks). */
    fun fragmentsWord(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "фрагмент"
            mod10 in 2..4 && mod100 !in 12..14 -> "фрагмента"
            else -> "фрагментов"
        }
    }

    /**
     * Status indicator: «Думает…» while the turn streams, otherwise the settled
     * fragment summary («1 фрагмент», «2 фрагмента», «5 фрагментов»).
     */
    fun statusLabel(thoughts: List<String>, streaming: Boolean): String? {
        val count = fragmentCount(thoughts)
        if (count == 0) return null
        if (streaming) return THINKING_LABEL
        return "$count ${fragmentsWord(count)}"
    }

    /** Full presentation of the accordion for the render layer. */
    fun present(thoughts: List<String>, expanded: Boolean, streaming: Boolean): ThinkingCardView {
        val body = bodyText(thoughts)
        if (body.isEmpty()) return ThinkingCardView.HIDDEN
        return ThinkingCardView(
            visible = true,
            chipLabel = CHIP_LABEL,
            statusLabel = statusLabel(thoughts, streaming),
            streaming = streaming,
            chevronUp = expanded,
            expanded = expanded,
            bodyText = body,
            bodyIndentDp = BODY_INDENT_DP,
            fragmentCount = fragmentCount(thoughts),
            copyLabel = COPY_LABEL,
            copySource = body,
            chipColor = MarsTheme.ACCENT_TEXT,
            bodyColor = MarsTheme.TEXT_EM,
            cardColor = MarsTheme.THOUGHT_BG,
            borderColor = MarsTheme.THOUGHT_BORDER,
        )
    }
}
