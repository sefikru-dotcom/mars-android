package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ru.mars.mobile.InteractiveDialogSpec.ANSWER_HINT
import ru.mars.mobile.InteractiveDialogSpec.SENDING_LABEL

/**
 * Custom Mars modal primitives: the full replacement for the stock Android AlertDialog.
 *
 * Every dialog is a plain [Dialog] with a transparent window, a dimmed scrim and a hand-built
 * obsidian surface (#10131B card, hairline #1E2433 border, 18dp corners). Components: ruby
 * header with title + queue badge, monospace code block for commands (no emojis, web code-card
 * parity), choice pills, ruby primary buttons and a quiet text action. All visuals come from
 * [MarsTheme] — the platform dialog chrome is never shown.
 */
object MarsDialog {

    /** Max dialog width on tablets/landscape; phones get screen width minus margins. */
    private const val MAX_WIDTH_DP = 460
    private const val SIDE_MARGIN_DP = 20

    /**
     * Creates and shows a Mars modal. [build] receives the vertical content root (already
     * padded 22dp) and returns the interactive controls so the caller can lock them while a
     * respond call is in flight. BACK cancels and routes to [onCancel] (silence ≠ consent).
     */
    fun show(
        activity: Activity,
        onCancel: (() -> Unit)? = null,
        build: (root: LinearLayout) -> List<View>,
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = GradientDrawable().apply {
            setColor(MarsTheme.SURFACE)
            cornerRadius = MarsTheme.dp(activity, 18).toFloat()
            setStroke(MarsTheme.dp(activity, 1), MarsTheme.BORDER_DIALOG)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = card
            val pad = MarsTheme.dp(activity, 22)
            setPadding(pad, pad, pad, pad)
        }
        build(root)
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(root, ViewGroup.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(true)
        if (onCancel != null) {
            dialog.setOnCancelListener { onCancel() }
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.72f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val metrics = activity.resources.displayMetrics
            val maxW = MarsTheme.dp(activity, MAX_WIDTH_DP)
            val width = minOf(metrics.widthPixels - 2 * MarsTheme.dp(activity, SIDE_MARGIN_DP), maxW)
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

    /** Ruby dialog header: accent bar + bold title + optional queue badge pill. */
    fun header(context: Context, title: String, badge: String?): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(MarsTheme.ACCENT)
                cornerRadius = MarsTheme.dp(context, 2).toFloat()
            }
        }, LinearLayout.LayoutParams(MarsTheme.dp(context, 3), MarsTheme.dp(context, 18)))
        row.addView(text(context, title, 17, MarsTheme.TEXT_STRONG, bold = true).apply {
            maxLines = 2
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = MarsTheme.dp(context, 10)
        })
        if (badge != null) {
            row.addView(
                pill(context, badge, MarsTheme.ACCENT_BG, MarsTheme.ACCENT_TEXT, MarsTheme.ACCENT),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = MarsTheme.dp(context, 8) }
            )
        }
        return withBottomMargin(context, row, 16)
    }

    /** Body paragraph (description / question). */
    fun paragraph(context: Context, body: String): View =
        withBottomMargin(context, text(context, body, 14, MarsTheme.TEXT_EM), 14)

    /**
     * Monospace code/command card: web code-block parity — near-black background (#090B10),
     * hairline border, small-caps ruby label (tool · risk). Returns null when [code] is blank
     * so the caller omits the block entirely.
     */
    fun codeBlock(context: Context, label: String?, code: String): View? {
        if (code.isBlank()) return null
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(MarsTheme.CODE_BG)
                cornerRadius = MarsTheme.dp(context, 12).toFloat()
                setStroke(MarsTheme.dp(context, 1), MarsTheme.CODE_BORDER)
            }
            val pad = MarsTheme.dp(context, 14)
            setPadding(pad, MarsTheme.dp(context, 12), pad, pad)
        }
        if (!label.isNullOrBlank()) {
            box.addView(text(context, label.uppercase(), 10, MarsTheme.ACCENT_TEXT, bold = true), lp())
        }
        box.addView(text(context, code, 13, MarsTheme.TEXT, bold = false).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod.getInstance()
            isHorizontalScrollBarEnabled = true
            setHorizontallyScrolling(true)
            maxLines = 10
        }, lp())
        return withBottomMargin(context, box, 14)
    }

    /** Small uppercase muted caption (e.g. above the pattern-key pills). */
    fun caption(context: Context, value: String): View =
        withBottomMargin(context, text(context, value.uppercase(), 10, MarsTheme.TEXT_MUTED, bold = true), 8)

    /**
     * Choice pill: quiet obsidian capsule with a hairline border; taps ripple.
     * Used for clarify presets and pattern keys (non-interactive when [onClick] is null).
     */
    fun pill(
        context: Context,
        label: String,
        bg: Int = MarsTheme.SURFACE_SUBTLE,
        fg: Int = MarsTheme.TEXT_EM,
        border: Int = MarsTheme.BORDER_MUTED,
        monospace: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): TextView {
        return text(context, label, 13, fg, bold = false).apply {
            if (monospace) typeface = Typeface.MONOSPACE
            background = MarsTheme.pill(context, bg, border)
            val padH = MarsTheme.dp(context, 16)
            val padV = MarsTheme.dp(context, 10)
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER
            maxLines = 2
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                foreground = ripple(context)
                setOnClickListener { onClick() }
            }
        }
    }

    /** Horizontally scrollable row of monospace pills (pattern keys). */
    fun pillRow(context: Context, items: List<String>): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { index, item ->
            row.addView(
                pill(context, item, monospace = true, fg = MarsTheme.TEXT_MUTED, border = MarsTheme.BORDER),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) marginStart = MarsTheme.dp(context, 8) }
            )
        }
        return withBottomMargin(context, HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, 14)
    }

    /**
     * Та же строка монospace-пилюль, но каждая кликабельна: [onClick] получает
     * текст пилюли. Пилюли focusable (ripple + D-pad/тач), горизонтальный скролл
     * строки сохраняется — навигация как у остального экрана.
     */
    fun pillRow(context: Context, items: List<String>, onClick: (String) -> Unit): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { index, item ->
            row.addView(
                pill(
                    context, item, monospace = true, fg = MarsTheme.TEXT_MUTED, border = MarsTheme.BORDER,
                    onClick = { onClick(item) },
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) marginStart = MarsTheme.dp(context, 8) }
            )
        }
        return withBottomMargin(context, HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, 14)
    }

    /** Vertical stack of full-width choice pills (clarify presets). */
    fun pillStack(context: Context, items: List<Pair<String, () -> Unit>>, accentLast: Boolean = false): View {
        val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, (label, onClick) ->
            val last = index == items.lastIndex
            val pill = if (accentLast && last) {
                pill(context, label, MarsTheme.ACCENT_BG, MarsTheme.ACCENT_TEXT, MarsTheme.ACCENT_BORDER, onClick = onClick)
            } else {
                pill(context, label, onClick = onClick)
            }
            stack.addView(pill, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = MarsTheme.dp(context, 8)
            })
        }
        return withBottomMargin(context, stack, 6)
    }

    /** Primary ruby action button (approve once / submit answer). */
    fun primaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        button(context, label, MarsTheme.ACCENT, MarsTheme.TEXT_STRONG, onClick)

    /** Secondary quiet button (allow-for-session / always grants). */
    fun secondaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        button(context, label, MarsTheme.SURFACE_SUBTLE, MarsTheme.TEXT_EM, onClick, MarsTheme.BORDER_MUTED)

    /** Destructive quiet button (deny): ruby text on a subtle ruby-tinted surface. */
    fun denyButton(context: Context, label: String, onClick: () -> Unit): Button =
        button(context, label, MarsTheme.ACCENT_BG, MarsTheme.ACCENT_TEXT, onClick, MarsTheme.ACCENT_BORDER)

    /**
     * Тихая текстовая кнопка (назад/отмена — без иконки). [iconRes] —
     * необязательная ведущая иконка из набора `ic_*`: глифы-символы в
     * подписях («＋ Новая задача») на HyperOS приходят из CJK-фолбэка шрифта,
     * не красятся tint'ом и съезжают по базовой линии. Вектор в compound
     * drawable ведёт себя как остальной UI: 16dp, цвет — токен палитры.
     */
    fun quietButton(
        context: Context,
        label: String,
        iconRes: Int? = null,
        onClick: () -> Unit,
    ): Button {
        return Button(context).apply {
            text = label
            setTextColor(MarsTheme.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ripple(context, radiusDp = 10)
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            if (iconRes != null) {
                val iconSize = MarsTheme.dp(context, 16)
                val icon = context.getDrawable(iconRes)?.mutate()?.apply {
                    setBounds(0, 0, iconSize, iconSize)
                    setTint(MarsTheme.TEXT_MUTED)
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = MarsTheme.dp(context, 6)
            }
            val padH = MarsTheme.dp(context, 12)
            setPadding(padH, MarsTheme.dp(context, 10), padH, MarsTheme.dp(context, 10))
            setOnClickListener { onClick() }
        }
    }

    /** Free-text answer field: obsidian input card with hairline border. */
    fun textField(context: Context, hint: String = ANSWER_HINT, initial: String = ""): EditText {
        return EditText(context).apply {
            setText(initial)
            this.hint = hint
            setHintTextColor(MarsTheme.TEXT_MUTED)
            setTextColor(MarsTheme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply {
                setColor(MarsTheme.CODE_BG)
                cornerRadius = MarsTheme.dp(context, 12).toFloat()
                setStroke(MarsTheme.dp(context, 1), MarsTheme.BORDER_MUTED)
            }
            val pad = MarsTheme.dp(context, 14)
            setPadding(pad, MarsTheme.dp(context, 12), pad, pad)
        }
    }

    /** Inline error strip (respond failures): ruby-tinted card, hidden until [setError]. */
    fun errorStrip(context: Context): TextView {
        return text(context, "", 12, MarsTheme.ACCENT_TEXT, bold = false).apply {
            visibility = View.GONE
            // Доступность: ошибку approval/clarify озвучивает TalkBack при смене текста.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            background = GradientDrawable().apply {
                setColor(MarsTheme.ACCENT_BG)
                cornerRadius = MarsTheme.dp(context, 10).toFloat()
                setStroke(MarsTheme.dp(context, 1), MarsTheme.ACCENT_BORDER)
            }
            val pad = MarsTheme.dp(context, 12)
            setPadding(pad, MarsTheme.dp(context, 10), pad, pad)
        }
    }

    /** Show/hide the inline error strip. */
    fun setError(strip: TextView, message: String?) {
        if (message.isNullOrBlank()) {
            strip.text = ""
            strip.visibility = View.GONE
        } else {
            strip.text = message
            strip.visibility = View.VISIBLE
        }
    }

    /**
     * Indeterminate progress row ("Отправка…") for a respond POST in flight: a slow network must
     * not look like a dead dialog. Hidden until [setProgress]; the row is a live region so
     * TalkBack announces the state change when it appears.
     */
    fun progressRow(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            contentDescription = SENDING_LABEL
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val size = MarsTheme.dp(context, 18)
        row.addView(
            ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
            },
            LinearLayout.LayoutParams(size, size),
        )
        row.addView(
            text(context, SENDING_LABEL, 12, MarsTheme.TEXT_MUTED).apply {
                setPadding(MarsTheme.dp(context, 8), 0, 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return withBottomMargin(context, row, 8)
    }

    /** Toggle the [progressRow] visibility. */
    fun setProgress(row: View?, visible: Boolean) {
        row?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Retry action after a transport failure: accent-tinted quiet button (48dp touch target). */
    fun retryButton(context: Context, label: String, onClick: () -> Unit): Button =
        button(context, label, MarsTheme.SURFACE_SUBTLE, MarsTheme.ACCENT_TEXT, onClick, MarsTheme.ACCENT_BORDER)

    /** Lock/unlock interactive controls while a respond call is in flight. */
    fun setResponding(controls: List<View>, responding: Boolean) {
        controls.forEach {
            it.isEnabled = !responding
            it.alpha = if (responding) 0.45f else 1f
        }
    }

    /** Row: first view weighted (fills), the rest wrap-content (submit + back). */
    fun buttonRow(context: Context, vararg views: View): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { index, view ->
            val weight = if (index == 0) 1f else 0f
            row.addView(view, LinearLayout.LayoutParams(
                if (weight > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight
            ).apply { if (index > 0) marginStart = MarsTheme.dp(context, 10) })
        }
        return row
    }

    /** Full-width block with a bottom margin. */
    fun block(context: Context, view: View, bottomDp: Int = 10): View =
        withBottomMargin(context, view, bottomDp)

    // --- low-level helpers ------------------------------------------------------------------

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private fun text(context: Context, value: String, sp: Int, color: Int, bold: Boolean = false): TextView {
        return TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }
    }

    private fun button(
        context: Context,
        label: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit,
        border: Int = 0,
    ): Button {
        return Button(context).apply {
            text = label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = MarsTheme.button(context, bg, radiusDp = 12, strokeColor = border)
            stateListAnimator = null
            minHeight = MarsTheme.dp(context, 48)
            minimumHeight = MarsTheme.dp(context, 48)
            setOnClickListener { onClick() }
        }
    }

    private fun ripple(context: Context, radiusDp: Int = 999): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(MarsTheme.TEXT_STRONG)
            cornerRadius = MarsTheme.dp(context, radiusDp).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(MarsTheme.RIPPLE), null, mask)
    }

    private fun lp(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun withBottomMargin(context: Context, view: View, dp: Int): View {
        return FrameLayout(context).apply {
            addView(view, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = MarsTheme.dp(context, dp)
            })
        }
    }
}
