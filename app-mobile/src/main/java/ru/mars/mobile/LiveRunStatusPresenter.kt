package ru.mars.mobile

/** Phase of the live run, shown on the status plate above the composer. */
enum class LiveRunPhase(val label: String) {
    ANALYZE("Анализирую код…"),
    EXECUTE("Выполняю команду…"),
    COMPOSE("Формирую ответ…"),
    /** Neutral fallback before any signal arrives. */
    THINK("Думаю…"),
}

/** Presentation model of the live-run status plate. Pure data — no Android imports. */
data class LiveRunStatusView(
    /** False when no turn is streaming — the plate is not rendered. */
    val visible: Boolean,
    val phase: LiveRunPhase?,
    /** Main label («Выполняю команду…»); empty when hidden. */
    val label: String,
    /** Secondary hint: the running tool as «Терминал · bash»; null when unknown. */
    val detailLabel: String?,
    /** True — the render layer shows its animated indicator (never a glyph/emoji). */
    val showSpinner: Boolean,
    // Palette tokens (MarsTheme constants, inlined at compile time).
    val labelColor: Int,
    val detailColor: Int,
    val accentColor: Int,
    val cardColor: Int,
    val borderColor: Int,
) {
    companion object {
        /** Shared hidden instance — no active turn. */
        val HIDDEN = LiveRunStatusView(
            visible = false,
            phase = null,
            label = "",
            detailLabel = null,
            showSpinner = false,
            labelColor = MarsTheme.TEXT_EM,
            detailColor = MarsTheme.TEXT_MUTED,
            accentColor = MarsTheme.ACCENT,
            cardColor = MarsTheme.SURFACE_CARD,
            borderColor = MarsTheme.BORDER,
        )
    }
}

/**
 * Pure presentation logic for the live-run plate above the composer:
 * maps the server phase string and the streaming signals (running tool,
 * thoughts, first text) onto a human phase with a stable priority.
 * No Android imports — JVM unit-testable.
 */
object LiveRunStatusPresenter {
    /** Separator between the tool family and the raw tool name in [LiveRunStatusView.detailLabel]. */
    const val DETAIL_SEPARATOR = " · "

    private val EXECUTE_MARKERS = listOf("tool", "command", "exec", "bash", "terminal")
    private val ANALYZE_MARKERS = listOf("think", "reason", "analy")
    private val COMPOSE_MARKERS = listOf("respond", "response", "answer", "compose", "stream", "reply")

    /** Phase implied by the raw server phase string; null when it maps to nothing known. */
    fun phaseFromServer(raw: String?): LiveRunPhase? {
        val value = raw?.trim()?.lowercase()
        if (value.isNullOrEmpty()) return null
        return when {
            EXECUTE_MARKERS.any { value.contains(it) } -> LiveRunPhase.EXECUTE
            ANALYZE_MARKERS.any { value.contains(it) } -> LiveRunPhase.ANALYZE
            COMPOSE_MARKERS.any { value.contains(it) } -> LiveRunPhase.COMPOSE
            else -> null
        }
    }

    /**
     * Resolves the plate state. Priority: explicit server phase → running tool →
     * first answer text → thought stream → neutral «Думаю…».
     */
    fun resolve(
        status: ChatStatus,
        phase: String? = null,
        runningToolName: String? = null,
        hasText: Boolean = false,
        hasThoughts: Boolean = false,
    ): LiveRunStatusView {
        if (status != ChatStatus.TYPING) return LiveRunStatusView.HIDDEN
        val resolved = phaseFromServer(phase)
            ?: when {
                !runningToolName.isNullOrBlank() -> LiveRunPhase.EXECUTE
                hasText -> LiveRunPhase.COMPOSE
                hasThoughts -> LiveRunPhase.ANALYZE
                else -> LiveRunPhase.THINK
            }
        val detail = runningToolName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            ToolCardPresenter.classify(it).label + DETAIL_SEPARATOR + it
        }
        return LiveRunStatusView(
            visible = true,
            phase = resolved,
            label = resolved.label,
            detailLabel = detail,
            showSpinner = true,
            labelColor = MarsTheme.TEXT_EM,
            detailColor = MarsTheme.TEXT_MUTED,
            accentColor = MarsTheme.ACCENT,
            cardColor = MarsTheme.SURFACE_CARD,
            borderColor = MarsTheme.BORDER,
        )
    }
}
