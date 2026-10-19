package ru.mars.tv

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.tv.TvInteractiveCardSpec.ApprovalOption
import ru.mars.tv.TvInteractiveCardSpec.ClarifyAction
import ru.mars.tv.TvInteractiveCardSpec.ClarifyMode
import ru.mars.tv.TvInteractiveCardSpec.ClarifyOption
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload

/**
 * Inline D-pad-focusable card rendered inside the TV transcript while the agent is blocked on an
 * approval/clarify attention request (WebUI-parity decision surface; TV has no modal dialogs —
 * the card lives in the message flow so the conversation stays visible).
 *
 * - approval -> title with queue badge, description, monospace command block, pattern chips and
 *   tiered decision buttons (ruby primary «Разрешить один раз», quiet session/always grants,
 *   ruby-tinted destructive «Отклонить», YOLO grant) + «Отмена» (cancel without responding);
 * - clarify -> question + preset chips with a trailing «Свой ответ» chip that swaps the card in
 *   place into a free-text editor (empty `choices_offered` starts directly in the editor).
 *
 * Every control is `focusable` and gets the [TvFocusLift] treatment; when the card is shown the
 * first control takes focus so the user can decide with the D-pad alone. [setResponding] locks
 * all controls while a respond call is in flight (no double-submit); [showError] surfaces the
 * failure inline without dismissing the card (retryable).
 *
 * Pure presentation: all option logic comes from [TvInteractiveCardSpec] (JVM-tested); user
 * actions are forwarded verbatim to [listener] (the [TvInteractiveController]).
 */
class TvInteractiveCard(context: Context) : LinearLayout(context) {

    private enum class Kind { APPROVAL, CLARIFY }

    /** Controller-side listener; clicks are forwarded after the card's own mode handling. */
    var listener: TvInteractiveListener? = null

    private var kind: Kind = Kind.APPROVAL
    private val controls = mutableListOf<View>()
    private lateinit var errorStrip: TextView
    private var clarifyPayload: ClarifyPayload? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_focus_card)
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        setPadding(dp(28), dp(20), dp(28), dp(20))
        errorStrip = TextView(context).apply {
            visibility = View.GONE
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.mars_status_error))
        }
    }

    // ---- binding ---------------------------------------------------------------

    /** Builds the approval decision card for [payload] (options gated by the payload flags). */
    fun bindApproval(payload: ApprovalPayload, yoloEnabled: Boolean = false) {
        kind = Kind.APPROVAL
        controls.clear()
        removeAllViews()

        addView(title(TvInteractiveCardSpec.approvalTitle(payload.pendingCount)))
        TvInteractiveCardSpec.approvalDescription(payload).takeIf { it.isNotEmpty() }?.let { description ->
            addView(TextView(context).apply {
                text = description
                textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.mars_text_em))
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        TvInteractiveCardSpec.approvalCodeLabel(payload)?.let { label ->
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.mars_muted))
                setPadding(0, dp(4), 0, 0)
            })
        }
        TvInteractiveCardSpec.approvalCodeBlock(payload).takeIf { it.isNotEmpty() }?.let { code ->
            addView(TextView(context).apply {
                text = code
                textSize = 17f
                typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(context, R.color.mars_text))
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(18), dp(12), dp(18), dp(12))
                val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            })
        }
        TvInteractiveCardSpec.approvalPatternKeys(payload).takeIf { it.isNotEmpty() }?.let { keys ->
            addView(chipRow(keys.map { "${TvInteractiveCardSpec.PATTERN_PREFIX}: $it" }))
        }

        addView(optionRow(TvInteractiveCardSpec.approvalOptions(payload, yoloEnabled).map { option ->
            cardButton(option.label, primary = TvInteractiveCardSpec.isPrimary(option), destructive = TvInteractiveCardSpec.isDeny(option)) {
                listener?.onApprovalChoice(option.choice, option.yolo)
            }
        }))
        addView(cancelRow { listener?.onApprovalCancelled() })
        addView(errorStrip)
    }

    /** Builds (or rebuilds, on mode swaps) the clarify card for [payload]. */
    fun bindClarify(payload: ClarifyPayload) {
        clarifyPayload = payload
        renderClarifyMode(TvInteractiveCardSpec.clarifyMode(payload))
    }

    private fun renderClarifyMode(mode: ClarifyMode) {
        kind = Kind.CLARIFY
        controls.clear()
        removeAllViews()
        val payload = clarifyPayload ?: return

        addView(title(TvInteractiveCardSpec.CLARIFY_TITLE))
        TvInteractiveCardSpec.clarifyQuestion(payload).takeIf { it.isNotEmpty() }?.let { question ->
            addView(TextView(context).apply {
                text = question
                textSize = 20f
                setTextColor(ContextCompat.getColor(context, R.color.mars_text_strong))
                setPadding(0, dp(6), 0, dp(10))
            })
        }
        when (mode) {
            is ClarifyMode.Presets -> {
                addView(optionRow(mode.options.map { option ->
                    cardButton(option.label, primary = false, destructive = false) {
                        when (val action = TvInteractiveCardSpec.onClarifyOptionActivated(mode, option)) {
                            is ClarifyAction.Respond -> listener?.onClarifyResponse(action.text)
                            is ClarifyAction.SwitchToFreeText -> renderClarifyMode(ClarifyMode.FreeText(action.presets))
                            ClarifyAction.None -> Unit
                        }
                    }
                }))
            }
            is ClarifyMode.FreeText -> {
                val editor = EditText(context).apply {
                    hint = TvInteractiveCardSpec.ANSWER_HINT
                    textSize = 20f
                    isSingleLine = true
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    setTextColor(ContextCompat.getColor(context, R.color.mars_text))
                    setHintTextColor(ContextCompat.getColor(context, R.color.mars_muted))
                    setBackgroundResource(R.drawable.bg_focus_input)
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    isFocusable = true
                    isFocusableInTouchMode = true
                    imeOptions = EditorInfo.IME_ACTION_SEND
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEND) { submit(this@apply); true } else false
                    }
                    TvFocusLift.attach(this)
                }
                controls += editor
                addView(editor.let {
                    val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(4)
                    it.layoutParams = lp
                    it
                })
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(12)
                    layoutParams = lp
                }
                val submit = cardButton(TvInteractiveCardSpec.SUBMIT_LABEL, primary = true, destructive = false) { submit(editor) }
                row.addView(submit)
                if (mode.presets.isNotEmpty()) {
                    val back = cardButton(TvInteractiveCardSpec.BACK_LABEL, primary = false, destructive = false) {
                        renderClarifyMode(ClarifyMode.Presets(
                            mode.presets.map { ClarifyOption.Preset(it) } + ClarifyOption.OwnAnswer
                        ))
                    }
                    (back.layoutParams as LayoutParams).marginStart = dp(12)
                    row.addView(back)
                }
                addView(row)
                // D-pad в поле свободного ответа: тот же перенос музыки
                // ([TvDpadTextInput]) — EditText сам съедает стрелки, поэтому без
                // перехвата из поля нельзя было уйти вниз на «Ответить»/«Назад», и
                // ответ на уточнение пультом был недостижим. Клавиатура — по нажатию,
                // при открытом IME стрелки остаются её.
                TvDpadTextInput.openImeOnClick(editor)
                TvDpadTextInput.escapeArrows(
                    editor,
                    imeVisible = { TvDpadTextInput.isImeVisible(editor) },
                    down = { TvDpadTextInput.focus(submit) },
                    right = { TvDpadTextInput.focus(submit) },
                )
                // Free text is the only answer path for an open question — the editor takes focus.
                post { editor.requestFocus() }
            }
        }
        addView(cancelRow { listener?.onClarifyCancelled() })
        addView(errorStrip)
    }

    private fun submit(editor: EditText) {
        val text = TvInteractiveCardSpec.clarifyFreeTextSubmit(editor.text.toString()) ?: return
        listener?.onClarifyResponse(text)
    }

    // ---- state ------------------------------------------------------------------

    /** Locks (respond in flight) or re-enables every control; an unresponsive D-pad double-press
     *  must never fire a second respond. */
    fun setResponding(responding: Boolean) {
        controls.forEach { it.isEnabled = !responding }
    }

    /** Inline ruby error strip; the card stays open so the user can retry. */
    fun showError(message: String) {
        errorStrip.text = message
        errorStrip.visibility = View.VISIBLE
    }

    fun clearError() {
        errorStrip.visibility = View.GONE
    }

    /** Moves D-pad focus to the first actionable control of the freshly shown card. */
    fun focusFirst() {
        controls.firstOrNull()?.requestFocus() ?: requestFocus()
    }

    /** BACK while a card is focused cancels it without responding (silence ≠ consent). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && hasFocus()) {
            when (kind) {
                Kind.APPROVAL -> listener?.onApprovalCancelled()
                Kind.CLARIFY -> listener?.onClarifyCancelled()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- builders ----------------------------------------------------------------

    private fun title(text: String) = TextView(context).apply {
        this.text = text
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.mars_accent_text))
    }

    private fun cardButton(label: String, primary: Boolean, destructive: Boolean, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(22), dp(12), dp(22), dp(12))
            setBackgroundResource(if (primary) R.drawable.bg_focus_accent else R.drawable.bg_focus_card)
            setTextColor(ContextCompat.getColor(context, when {
                primary -> R.color.mars_on_accent
                destructive -> R.color.mars_status_error
                else -> R.color.mars_text
            }))
            isFocusable = true
            isFocusableInTouchMode = false
            TvFocusLift.attach(this)
            setOnClickListener { onClick() }
            controls += this
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(12)
            layoutParams = lp
        }

    /** Horizontal row of decision buttons; long lists wrap onto a second row (TV-safe). */
    private fun optionRow(buttons: List<Button>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14)
            layoutParams = lp
        }
        buttons.forEach { row.addView(it) }
        return row
    }

    private fun cancelRow(onCancel: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        }
        val cancel = cardButton(TvInteractiveCardSpec.CANCEL_LABEL, primary = false, destructive = false, onClick = onCancel).apply {
            setTextColor(ContextCompat.getColor(context, R.color.mars_muted))
        }
        row.addView(cancel)
        return row
    }

    /** Static, non-clickable caption chips (pattern keys). */
    private fun chipRow(labels: List<String>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        labels.forEach { label ->
            row.addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.mars_muted))
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(8)
                layoutParams = lp
            })
        }
        return row
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
