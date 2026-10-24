package ru.mars.tv

import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload

/**
 * TV implementation of [TvInteractiveSurface]: renders the interactive cards INSIDE the
 * transcript container (no modals on TV) so the conversation stays visible while the agent
 * waits, and everything is reachable with the D-pad.
 *
 * - show -> a fresh [TvInteractiveCard] is appended below the last message, scrolled into view and
 *   its first control takes focus (D-pad-only decision flow);
 * - dismiss -> the card is removed; when the LAST card goes away [onAllCardsCleared] fires so the
 *   host can return focus to the composer — the turn is back to the plain stream;
 * - errors render inline on the open card ([TvInteractiveCard.showError]); notices (stale prompts)
 *   become a muted transcript line — they must survive the card dismissal that caused them.
 *
 * Pure view bookkeeping: no network, no decisions — those live in [TvInteractiveController].
 */
class TvChatInteractiveSurface(
    private val container: LinearLayout,
    private val onAllCardsCleared: () -> Unit = {},
    private val onCardShown: () -> Unit = {},
) : TvInteractiveSurface {

    /** Controller wired by the host right after construction (card clicks route to it). */
    var listener: TvInteractiveListener? = null

    private var approvalCard: TvInteractiveCard? = null
    private var clarifyCard: TvInteractiveCard? = null

    override fun showApproval(payload: ApprovalPayload) {
        dismissApproval()
        approvalCard = buildCard { it.bindApproval(payload) }
        onCardShown()
    }

    override fun showClarify(payload: ClarifyPayload) {
        dismissClarify()
        clarifyCard = buildCard { it.bindClarify(payload) }
        onCardShown()
    }

    override fun setApprovalResponding(responding: Boolean) {
        approvalCard?.apply {
            setResponding(responding)
            if (!responding) clearError()
        }
    }

    override fun setClarifyResponding(responding: Boolean) {
        clarifyCard?.apply {
            setResponding(responding)
            if (!responding) clearError()
        }
    }

    override fun dismissApproval() {
        approvalCard?.let { container.removeView(it) }
        approvalCard = null
        notifyIfCleared()
    }

    override fun dismissClarify() {
        clarifyCard?.let { container.removeView(it) }
        clarifyCard = null
        notifyIfCleared()
    }

    /** Inline on the topmost open card; a muted transcript line when no card is open. */
    override fun showCardError(message: String) {
        val card = clarifyCard ?: approvalCard
        if (card != null) card.showError(message) else appendNoticeLine(message, error = true)
    }

    /** Stale/terminal notices outlive the dismissed card, so they go into the transcript. */
    override fun showCardNotice(message: String) {
        appendNoticeLine(message, error = false)
    }

    /** True while any interactive card is on screen — the host keeps the composer locked. */
    val hasOpenCard: Boolean get() = approvalCard != null || clarifyCard != null

    /** Host cleanup («Новый чат», destroy): drop both cards without the focus callback. */
    fun removeAllCards() {
        approvalCard?.let { container.removeView(it) }
        clarifyCard?.let { container.removeView(it) }
        approvalCard = null
        clarifyCard = null
    }

    private fun buildCard(bind: (TvInteractiveCard) -> Unit): TvInteractiveCard {
        val card = TvInteractiveCard(container.context).also {
            it.listener = listener
            bind(it)
        }
        container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        // D-pad entry point: the first decision control of the new card takes focus.
        card.focusFirst()
        return card
    }

    private fun appendNoticeLine(message: String, error: Boolean) {
        val line = TextView(container.context).apply {
            text = message
            tvTextSize(R.dimen.tv_text_body)
            setTextColor(ContextCompat.getColor(context,
                if (error) R.color.mars_status_error else R.color.mars_muted_on_glass))
            setPadding(dp(24), dp(6), dp(24), dp(6))
        }
        container.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        onCardShown()
    }

    private fun notifyIfCleared() {
        if (!hasOpenCard) onAllCardsCleared()
    }

    private fun dp(value: Int) = (value * container.resources.displayMetrics.density).toInt()
}
