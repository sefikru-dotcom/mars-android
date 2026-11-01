package ru.mars.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Тонкая горизонтальная полоса заполнения окна контекста локального чата.
 *
 * Зачем отдельный View, а не текстовая пилюля «3.1k / 1M · 0%»: цифра без шкалы не
 * отвечает на единственный вопрос, который у владельца и был, — «сколько ещё влезет».
 * В серверном чате для этого стоит кольцо ([ContextRingView]); здесь — полоса, потому
 * что в строке композера она занимает остаток ширины и не спорит с подписью токенов.
 *
 * Размеры: высота [BAR_HEIGHT_DP], ширина — по мере родителя, но не больше
 * [MAX_WIDTH_DP]. Взвешенное размещение (`0, height, 1f`) даёт `min-width: 0`:
 * длинная подпись «12.4k / 1M» сжимает полосу, а не выдавливает её за край экрана.
 * На POCO F5 (1080×2400, 6.67") полоса остаётся читаемой и строку не растягивает.
 *
 * Цвет решает не этот класс, а [LocalContextPolicy] → [ContextFillBarView.toneOf]:
 * зелёный до 50%, жёлтый до 75%, красный выше — те же пороги, что у серверного кольца.
 * Рисование — единственная обязанность этого View: процент, кламп и порог приходят
 * готовыми, второго набора правил здесь нет.
 */
class ContextFillBarView(context: Context) : View(context) {

    private val heightPx = MarsTheme.dp(context, BAR_HEIGHT_DP)
    private val maxWidthPx = MarsTheme.dp(context, MAX_WIDTH_DP)
    private val radiusPx = heightPx / 2f

    private val bounds = RectF()

    /** Лоток: виден всегда, даже при нулевом заполнении — иначе полосы «нет». */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MarsTheme.BORDER_MUTED
    }

    /** Заливка: цвет по порогу, ширина по проценту. */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MarsTheme.STATUS_ONLINE
    }

    /** Доля заполнения 0f..1f. */
    private var fraction = 0f

    init {
        // Полоса — индикатор, а не кнопка: тап обрабатывает строка композера целиком.
        isClickable = false
        isFocusable = false
        minimumWidth = 0
    }

    /**
     * Показать заполнение: [percent] клампится к 0..100 (null — пустой лоток),
     * [color] — уже выбранный цвет порога.
     */
    fun render(percent: Int?, color: Int) {
        fraction = ((percent ?: 0).coerceIn(0, 100)) / 100f
        fillPaint.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        // Ширина — «сколько дали, но не больше предела»: на планшете полоса не растянулась
        // бы на полэкрана, а на телефоне не спорила с подписью токенов.
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> maxWidthPx
            else -> minOf(available, maxWidthPx)
        }
        setMeasuredDimension(width, resolveSize(heightPx, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radiusPx, radiusPx, trackPaint)
        val filled = width * fraction.coerceIn(0f, 1f)
        // Процент толщиной меньше радиуса скругления не нарисовался бы вовсе: тогда
        // честнее показать точку заполнения радиусом, чем пустой лоток при «2%».
        if (filled > 0f) {
            val end = if (filled < radiusPx) radiusPx else filled
            bounds.set(0f, 0f, end.coerceAtMost(width.toFloat()), height.toFloat())
            canvas.drawRoundRect(bounds, radiusPx, radiusPx, fillPaint)
        }
    }

    companion object {
        /** Высота полосы: 4dp — тонкая, но видимая на 1080p-экране телефона. */
        const val BAR_HEIGHT_DP = 4

        /** Предел ширины: на широком экране полоса не тянется через всю строку. */
        const val MAX_WIDTH_DP = 160

        /**
         * Цвет порога: те же три состояния, что у [LocalContextPolicy.level].
         *
         * Один источник для полосы и для процента рядом с ней — иначе «жёлтая полоса,
         * зелёная цифра» врали бы друг о друге.
         */
        fun toneOf(level: LocalContextLevel?): Int = when (level) {
            LocalContextLevel.WARNING -> MarsTheme.STATUS_WARNING
            LocalContextLevel.CRITICAL -> MarsTheme.STATUS_ERROR
            LocalContextLevel.NORMAL, null -> MarsTheme.STATUS_ONLINE
        }
    }
}