package ru.mars.mobile

import kotlin.math.abs

/**
 * Чистая политика свайпов плеера (без Android-импортов — проверяется JVM-тестами).
 *
 * Зачем отдельный файл: в `MusicActivity` заполненный `OnTouchListener` непокрываем —
 * инструментального слоя в проекте нет. Поэтому здесь живут ровно те решения, в которых
 * легко ошибиться и которые ломают UX:
 *
 * - **порог по расстоянию** — палец всегда «дрожит» на несколько пикселей, и без порога
 *   обычный тап по обложке превращался бы в перелистывание трека;
 * - **классификация направления** — по доминирующей оси с коэффициентом [AXIS_DOMINANCE]:
 *   жест «наискосок» не должен угадываться ни как перелистывание, ни как закрытие;
 * - **зоны контролов** — жест, начавшийся на перемотке или кнопке, принадлежит этому
 *   контролу: drag по SeekBar обязан оставаться перемоткой, а не сменой трека;
 * - **что означает жест на каждой поверхности** — на нижней полосе вверх открывает, на
 *   раскрытом плеере вниз закрывает, влево/вправо листают треки; вверх на раскрытом
 *   плеере не делает ничего (там уже всё раскрыто).
 *
 * Порог задан в dp и переводится в пиксели плотностью экрана: 48dp — меньше половины
 * высоты полосы плеера, но заметно больше дрожания пальца и системного `touch slop`.
 */
object MusicGesturePolicy {

    /** Минимальный путь пальца, чтобы жест считался свайпом (dp). */
    const val MIN_SWIPE_DP = 48

    /**
     * Во сколько раз доминирующая ось должна превышать вторую, чтобы направление
     * считалось однозначным. При 1.5 диагональ «наискосок» (100/100) даёт [Swipe.NONE].
     */
    const val AXIS_DOMINANCE = 1.5f

    /** Порог никогда не схлопывается в ноль: нулевой порог = любой тап листает трек. */
    private const val MIN_THRESHOLD_PX = 1

    /** Направление свайпа ПО ОСИ; смысл («открыть», «следующий») даёт поверхность. */
    enum class Swipe { NONE, LEFT, RIGHT, UP, DOWN }

    /** Действие плеера, в которое превращается свайп конкретной поверхности. */
    enum class Direction { OPEN, CLOSE, NEXT, PREVIOUS }

    /** Порог свайпа в пикселях для плотности экрана [density] (dp по умолчанию — 48). */
    fun thresholdPx(density: Float, dp: Int = MIN_SWIPE_DP): Int =
        (dp * density).toInt().coerceAtLeast(MIN_THRESHOLD_PX)

    /**
     * Классификация жеста по смещению пальца от точки касания.
     *
     * Возвращает [Swipe.NONE] для тапа, короткого drag'а и «наискосок»-жеста: вызывающий
     * код тогда трактует короткое смещение как тап, а не как команду.
     */
    fun classify(dxPx: Int, dyPx: Int, thresholdPx: Int): Swipe {
        val threshold = thresholdPx.coerceAtLeast(MIN_THRESHOLD_PX)
        val dx = abs(dxPx)
        val dy = abs(dyPx)
        val horizontal = dx >= dy * AXIS_DOMINANCE
        val vertical = dy >= dx * AXIS_DOMINANCE
        return when {
            horizontal && dx >= threshold -> if (dxPx < 0) Swipe.LEFT else Swipe.RIGHT
            vertical && dy >= threshold -> if (dyPx < 0) Swipe.UP else Swipe.DOWN
            else -> Swipe.NONE
        }
    }

    /** Нижняя полоса: вверх открывает раскрытый плеер, остальные направления — не её. */
    fun barSwipe(swipe: Swipe): Direction? = when (swipe) {
        Swipe.UP -> Direction.OPEN
        Swipe.LEFT, Swipe.RIGHT, Swipe.DOWN, Swipe.NONE -> null
    }

    /**
     * Раскрытый плеер: вниз закрывает, влево — следующий трек, вправо — предыдущий.
     * Вверх не делает ничего: раскрывать уже нечего.
     */
    fun fullPlayerSwipe(swipe: Swipe): Direction? = when (swipe) {
        Swipe.DOWN -> Direction.CLOSE
        Swipe.LEFT -> Direction.NEXT
        Swipe.RIGHT -> Direction.PREVIOUS
        Swipe.UP, Swipe.NONE -> null
    }

    /**
     * Прямоугольник контрола в координатах контейнера-приёмника жеста.
     * Полуоткрытый справа/снизу (`until`), чтобы соседние контролы не перекрывались.
     */
    data class Zone(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
        val isEmpty: Boolean get() = right <= left || bottom <= top
    }

    /**
     * Начался ли жест на контроле: такой touch принадлежит контролу (перемотка/тап по
     * кнопке) и не может стать командой плеера. Пустой список зон — жест свободен.
     */
    fun startsOnControl(x: Int, y: Int, zones: List<Zone>): Boolean =
        zones.any { !it.isEmpty && it.contains(x, y) }
}
