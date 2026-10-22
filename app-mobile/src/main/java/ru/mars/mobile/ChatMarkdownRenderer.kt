package ru.mars.mobile

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.method.LinkMovementMethod
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.*
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat

object ChatMarkdownRenderer {

    /**
     * Состояние инкрементальной отрисовки одной markdown-секции: какие единицы
     * ([MarkdownUnit]) уже нарисованы. Живёт столько же, сколько секция (карточка),
     * и передаётся из кадра стрима в кадр — без него переиспользовать префикс нельзя.
     * Все обращения — только с главного потока.
     */
    class MarkdownRenderState {
        internal var units: List<MarkdownUnit> = emptyList()
    }

    /**
     * Рисует markdown-блоки в [parent] ИНКРЕМЕНТАЛЬНО: неизменившийся префикс view
     * остаётся на месте, сносится и перерисовывается только хвост, начиная с первой
     * изменившейся единицы ([StreamingMarkdownPlan.reusablePrefix]). Конечный вид
     * не меняется: тот же набор view в том же порядке.
     */
    fun render(
        context: Context,
        blocks: List<ContentBlock>,
        parent: LinearLayout,
        user: Boolean,
        messageIndex: Int,
        state: MarkdownRenderState,
        isCodeBlockExpanded: (Int, Int) -> Boolean,
        toggleCodeBlock: (Int, Int) -> Unit,
        copyMessage: (String) -> Unit,
        openImage: (String) -> Unit,
        openUrl: (String) -> Unit,
    ) {
        fun dp(value: Int) = MarsTheme.dp(context, value)

        val units = StreamingMarkdownPlan.units(blocks)
        // Инвариант «единица ↔ ребёнок parent» может быть нарушен извне (секцию чистили
        // не этим рендерером) — тогда пересобираем всё, а не рисуем поверх.
        val intact = parent.childCount == state.units.size
        val reuse = if (intact) StreamingMarkdownPlan.reusablePrefix(state.units, units) else 0
        if (parent.childCount > reuse) parent.removeViews(reuse, parent.childCount - reuse)

        for (unitIndex in reuse until units.size) {
            when (val unit = units[unitIndex]) {
                is MarkdownUnit.Line -> {
                    val line = unit.line
                    val tv = TextView(context).apply {
                        movementMethod = LinkMovementMethod.getInstance()
                        setTextColor(if (user) MarsTheme.TEXT_STRONG else MarsTheme.TEXT)
                        textSize = 15f
                        setPadding(0, dp(2), 0, dp(2))
                        setLineSpacing(dp(2).toFloat(), 1f)
                    }
                    val ssb = SpannableStringBuilder()
                    // Флаги построения: цитата добавляется обёрткой (бар + текст),
                    // разделитель — отдельным View, поэтому общий addView ниже
                    // знает, что именно класть в parent (инвариант «одна единица
                    // <-> один ребёнок» сохраняется: и обёртка, и View — один ребёнок).
                    var quoteLine = false
                    var skipTv = false
                    when (line) {
                        is RichLine.Header -> {
                            tv.textSize = when (line.level) {
                                1 -> 18f
                                2 -> 17f
                                else -> 16f
                            }
                            tv.setTypeface(null, Typeface.BOLD)
                            appendInlineSpans(ssb, line.spans, openImage, openUrl)
                        }
                        is RichLine.Bullet -> {
                            // Маркер списка рисует canvas (BulletSpan), а не глиф «•»: в шрифте
                            // OEM он подменяется эмодзи-версией и съезжает по базовой линии.
                            // Отступ держит gapWidth, а не пробелы в тексте.
                            val bulletStart = ssb.length
                            appendInlineSpans(ssb, line.spans, openImage, openUrl)
                            ssb.setSpan(
                                BulletSpan(dp(10), MarsTheme.TEXT_MUTED),
                                bulletStart,
                                ssb.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        is RichLine.Numbered -> {
                            ssb.append("${line.number} ")
                            appendInlineSpans(ssb, line.spans, openImage, openUrl)
                        }
                        is RichLine.Quote -> {
                            // Цитата — блок, а не «палочка из пробела».
                            // Раньше акцентный BackgroundColorSpan красил ОДИН
                            // пробел в начале строки: бар был высотой с пробел,
                            // не тянулся на перенос и рвался на каждой второй
                            // строке цитаты. Теперь подложку несёт
                            // BackgroundColorSpan(QUOTE_BG) на всём тексте
                            // (тянется по строкам сам), а вертикальный бар
                            // рисует View слева — см. quoteRow ниже.
                            val quoteStart = ssb.length
                            appendInlineSpans(ssb, line.spans, openImage, openUrl)
                            if (ssb.length > quoteStart) {
                                ssb.setSpan(
                                    BackgroundColorSpan(MarsTheme.QUOTE_BG),
                                    quoteStart,
                                    ssb.length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                            }
                            tv.setTypeface(null, Typeface.ITALIC)
                            tv.setTextColor(MarsTheme.TEXT_EM)
                            tv.setPadding(dp(10), dp(3), dp(8), dp(3))
                            quoteLine = true
                        }
                        is RichLine.TaskItem -> {
                            val iconStart = ssb.length
                            ssb.append("  ")
                            val iconRes = if (line.checked) R.drawable.ic_check else R.drawable.ic_checkbox_empty
                            ssb.setSpan(
                                ImageSpan(context, iconRes, ImageSpan.ALIGN_BASELINE),
                                iconStart,
                                ssb.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            val start = ssb.length
                            appendInlineSpans(ssb, line.spans, openImage, openUrl)
                            if (line.checked) {
                                ssb.setSpan(StrikethroughSpan(), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                ssb.setSpan(ForegroundColorSpan(MarsTheme.TEXT_MUTED), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                        is RichLine.Normal -> {
                            appendInlineSpans(ssb, line.spans, openImage, openUrl)
                        }
                        is RichLine.Divider -> {
                            // Разделитель — настоящий View 1dp, а не пустой
                            // TextView с фоном: TextView в этом месте ещё и
                            // мерил текст (высота строки), из-за чего линия
                            // «плыла» по высоте между блоками.
                            skipTv = true
                            parent.addView(
                                View(context).apply { setBackgroundColor(MarsTheme.DIVIDER) },
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    dp(1)
                                ).apply {
                                    topMargin = dp(8)
                                    bottomMargin = dp(8)
                                }
                            )
                        }
                    }
                    tv.text = ssb
                    if (skipTv) continue
                    if (quoteLine) {
                        parent.addView(quoteRow(context, tv))
                    } else {
                        parent.addView(tv)
                    }
                }
                is MarkdownUnit.Code -> {
                    val blockIndex = unit.blockIndex
                    val block = unit.block
                    val expanded = isCodeBlockExpanded(messageIndex, blockIndex)
                    val view = CodeBlockPresenter.present(block, expanded)
                    val box = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), dp(6), dp(6), dp(6))
                        background = MarsTheme.card(context, MarsTheme.CODE_BG, radiusDp = 10, strokeColor = MarsTheme.CODE_BORDER)
                    }
                    // Block header: language title on the left, copy action on the right.
                    val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                    bar.addView(TextView(context).apply {
                        text = view.languageLabel
                        textSize = 11f
                        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                        setTextColor(MarsTheme.ACCENT_TEXT)
                        setPadding(dp(2), 0, dp(8), 0)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    val copyCodeBtn = MarsTheme.circleIconButton(
                        context,
                        R.drawable.ic_copy,
                        "Копировать код",
                        bg = MarsTheme.SURFACE,
                        iconTint = MarsTheme.TEXT,
                        sizeDp = 28,
                        strokeColor = 0
                    ).apply {
                        setOnClickListener {
                            copyMessage(view.fullCode)
                            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                        }
                    }
                    bar.addView(copyCodeBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
                    box.addView(bar)
                    box.addView(HorizontalScrollView(context).apply {
                        addView(TextView(context).apply {
                            text = view.visibleCode
                            setTextColor(MarsTheme.TEXT)
                            typeface = Typeface.MONOSPACE
                            textSize = 13f
                            setPadding(0, dp(4), dp(16), dp(8))
                        })
                    })
                    // Long listings are collapsed by default; the toggle flips the state.
                    val toggleLabel = view.toggleLabel
                    if (toggleLabel != null) {
                        box.addView(Button(context).apply {
                            text = toggleLabel
                            textSize = 11f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(MarsTheme.ACCENT_TEXT)
                            chevronStart(context, if (view.expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down, MarsTheme.ACCENT_TEXT)
                            background = null
                            isAllCaps = false
                            setPadding(0, 0, 0, 0)
                            minimumHeight = 0
                            minHeight = 0
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL
                            setOnClickListener {
                                toggleCodeBlock(messageIndex, blockIndex)
                            }
                        }, LinearLayout.LayoutParams(-1, dp(26)))
                    }
                    parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                }
                is MarkdownUnit.Table -> {
                    val block = unit.block
                    // Card with a HorizontalScrollView around a TableLayout so wide tables
                    // scroll sideways smoothly instead of breaking the phone layout.
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = MarsTheme.card(context, MarsTheme.SURFACE_CARD, radiusDp = 12, strokeColor = MarsTheme.BORDER)
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                    val table = TableLayout(context).apply {
                        // Thin themed divider between rows instead of hardcoded paints.
                        dividerDrawable = GradientDrawable().apply {
                            setColor(MarsTheme.BORDER)
                            setSize(dp(1), dp(1))
                        }
                        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                    }
                    fun buildTableRow(cells: List<List<InlineSpan>>, header: Boolean): TableRow =
                        TableRow(context).apply {
                            if (header) setBackgroundColor(MarsTheme.SURFACE_SUBTLE)
                            cells.forEach { spans ->
                                val cell = TextView(context).apply {
                                    movementMethod = LinkMovementMethod.getInstance()
                                    textSize = 13f
                                    setLineSpacing(dp(2).toFloat(), 1f)
                                    setPadding(dp(12), dp(8), dp(12), dp(8))
                                    if (header) {
                                        setTextColor(MarsTheme.TEXT_STRONG)
                                        setTypeface(null, Typeface.BOLD)
                                    } else {
                                        setTextColor(MarsTheme.TEXT)
                                    }
                                    val cellText = SpannableStringBuilder()
                                    appendInlineSpans(cellText, spans, openImage, openUrl)
                                    text = cellText
                                }
                                addView(cell)
                            }
                        }
                    table.addView(buildTableRow(block.headers, header = true))
                    block.rows.forEach { row -> table.addView(buildTableRow(row, header = false)) }
                    card.addView(HorizontalScrollView(context).apply { addView(table) })
                    parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                }
            }
        }
        state.units = units
    }

    /**
     * Строка цитаты: вертикальный бар слева + текст с подложкой.
     *
     * Бар — настоящий View шириной 3dp во всю высоту строки (включая переносы),
     * а не крашеный пробел в тексте: BackgroundColorSpan на символе даёт
     * прямоугольник высотой с глиф и рвётся между строками. Подложку текста
     * несёт [BackgroundColorSpan] в самом тексте (MarsTheme.QUOTE_BG).
     */
    private fun quoteRow(context: Context, tv: TextView): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                View(context).apply { setBackgroundColor(MarsTheme.QUOTE_BAR) },
                LinearLayout.LayoutParams(
                    MarsTheme.dp(context, 3),
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun appendInlineSpans(
        builder: SpannableStringBuilder,
        spans: List<InlineSpan>,
        openImage: (String) -> Unit,
        openUrl: (String) -> Unit,
    ) {
        spans.forEach { span ->
            val start = builder.length
            when (span) {
                is InlineSpan.Normal -> builder.append(span.text)
                is InlineSpan.Bold -> {
                    builder.append(span.text)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Italic -> {
                    builder.append(span.text)
                    builder.setSpan(StyleSpan(Typeface.ITALIC), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Monospace -> {
                    builder.append(span.text)
                    builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(BackgroundColorSpan(MarsTheme.SURFACE_SUBTLE), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Link -> {
                    builder.append(span.title)
                    // Голые URL линкуются с title = url, поэтому сам адрес остаётся
                    // в tv.text — TalkBack читает его как обычный текст.
                    val style = LinkRenderPolicy.drawState(MarsTheme.ACCENT_TEXT)
                    builder.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            // Image links open in the fullscreen lightbox, everything else in a browser.
                            if (ImageLightboxPolicy.isImageUrl(span.url)) {
                                openImage(span.url)
                            } else {
                                openUrl(span.url)
                            }
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = style.color
                            ds.isUnderlineText = style.underline
                            ds.bgColor = style.background
                        }
                    }, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Strikethrough -> {
                    builder.append(span.text)
                    builder.setSpan(StrikethroughSpan(), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Image -> {
                    // Markdown image: a clickable accent label — tap opens the fullscreen viewer.
                    builder.append(ImageLightboxPolicy.inlineLabel(span.alt))
                    val style = LinkRenderPolicy.drawState(MarsTheme.ACCENT_TEXT)
                    builder.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) = openImage(span.url)
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = style.color
                            ds.isUnderlineText = style.underline
                            ds.bgColor = style.background
                        }
                    }, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }


}

private fun Button.chevronStart(context: Context, drawableRes: Int, tint: Int) {
    setCompoundDrawablesRelativeWithIntrinsicBounds(ContextCompat.getDrawable(context, drawableRes), null, null, null)
    compoundDrawableTintList = android.content.res.ColorStateList.valueOf(tint)
    compoundDrawablePadding = MarsTheme.dp(context, 6)
}
