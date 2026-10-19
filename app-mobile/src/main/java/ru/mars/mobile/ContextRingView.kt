package ru.mars.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

/**
 * Компактное кольцо заполнения контекста — паритет с `.ctx-ring` / `.ctx-ring-svg` WebUI
 * (`static/style.css`: 24×24, `r=9.75`, `stroke-width:3`, `transform:rotate(-90deg)`,
 * `stroke-linecap:round`, центр — диск 15px с числом 8px). Дуга рисуется по часовой от
 * верхней точки, концы скруглены, число в центре — голый процент без знака «%»:
 * в кольце 24dp «42%» уже наезжает на дугу.
 *
 * Зачем отдельный View вместо текстовой пилюли `42% · 12k/200k`: пилюля занимала место
 * в строке чипов и читалась как кнопка, а кольцо — индикатор. Размер фиксирован (24dp),
 * поэтому композер не растёт; тап открывает детали окна контекста (тот же диалог,
 * что был у пилюли).
 *
 * Всё, что показывать, решает чистая [ContextBudgetPolicy] ([ContextPillView]);
 * этот слой только рисует: доля, порог → цвет, состояние сжатия.
 */
class ContextRingView(context: Context) : View(context) {

    private val sizePx = MarsTheme.dp(context, RING_SIZE_DP).toFloat()
    /** Радиус дуги и штрих — те же пропорции, что в SVG WebUI (`9.75` и `3` при `24`). */
    private val radiusPx = sizePx * (RADIUS_SVG / VIEWBOX_SVG)
    private val strokePx = sizePx * (STROKE_SVG / VIEWBOX_SVG)

    private val arcBounds = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        color = MarsTheme.BORDER_MUTED
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        color = MarsTheme.STATUS_ONLINE
    }

    /** Диск под числом: перекрывает дугу, чтобы цифры не сливались со штрихом. */
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MarsTheme.BG
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, LABEL_SP, context.resources.displayMetrics
        )
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = MarsTheme.TEXT_MUTED
    }

    /** Доля заполнения 0f..1f и число в центре: null — кольцо без подписи. */
    private var fraction = 0f
    private var label: String? = null

    init {
        minimumWidth = sizePx.toInt()
        minimumHeight = sizePx.toInt()
    }

    /**
     * Отрисовка по решению [ContextBudgetPolicy.pill]: процент, цвет порога,
     * «Сжатие…» (дуга полная, в центре многоточие), невидимая пилюля — пусто.
     */
    fun render(pill: ContextPillView) {
        if (!pill.visible) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        val tone = when (pill.level) {
            ContextBudgetLevel.CRITICAL -> MarsTheme.STATUS_ERROR
            ContextBudgetLevel.WARNING -> MarsTheme.STATUS_WARNING
            ContextBudgetLevel.NORMAL, null -> MarsTheme.STATUS_ONLINE
        }
        valuePaint.color = tone
        labelPaint.color = tone
        val percent = pill.percent
        if (pill.compression) {
            fraction = 1f
            label = "…"
        } else if (percent != null && percent in 0..100) {
            fraction = percent / 100f
            label = percent.toString()
        } else {
            fraction = 0f
            label = null
        }
        contentDescription = pill.contentDescription
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = sizePx.toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        arcBounds.set(cx - radiusPx, cy - radiusPx, cx + radiusPx, cy + radiusPx)

        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        if (fraction > 0f) {
            // Старт сверху (−90°), по часовой — как `transform: rotate(-90deg)` в WebUI.
            canvas.drawArc(arcBounds, -90f, 360f * fraction.coerceIn(0f, 1f), false, valuePaint)
        }
        val text = label ?: return
        canvas.drawCircle(cx, cy, sizePx * (CENTER_SVG / VIEWBOX_SVG), centerPaint)
        val baseline = cy - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(text, cx, baseline, labelPaint)
    }

    private companion object {
        const val RING_SIZE_DP = 24
        /** Геометрия SVG WebUI: viewBox 24, радиус 9.75, штрих 3, центральный диск 15. */
        const val VIEWBOX_SVG = 24f
        const val RADIUS_SVG = 9.75f
        const val STROKE_SVG = 3f
        const val CENTER_SVG = 7.5f
        const val LABEL_SP = 8f
    }
}
