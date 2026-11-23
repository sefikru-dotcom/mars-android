package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты [MusicGesturePolicy]: порог, классификация направления и смысл жеста на
 * каждой поверхности.
 *
 * Регрессы, которые ловит тест:
 * - нулевой/микроскопический порог — тап по обложке превращается в перелистывание;
 * - «наискосок» не должен угадываться как команда;
 * - вверх на раскрытом плеере и вниз на полосе — не команды (раньше их просто не было,
 *   а теперь легко навесить «на всякий случай»);
 * - жест, начавшийся на SeekBar/кнопке, — не свайп.
 */
class MusicGesturePolicyTest {

    // ---- порог ----

    @Test fun thresholdScalesWithDensity() {
        assertEquals(48, MusicGesturePolicy.thresholdPx(density = 1f))
        assertEquals(96, MusicGesturePolicy.thresholdPx(density = 2f))
        assertEquals(144, MusicGesturePolicy.thresholdPx(density = 3f))
    }

    @Test fun thresholdNeverCollapsesToZero() {
        // Нулевой порог = любой тап листает трек: даже при density 0 порог >= 1.
        assertEquals(1, MusicGesturePolicy.thresholdPx(density = 0f))
        assertTrue(MusicGesturePolicy.thresholdPx(density = 0.0001f) >= 1)
    }

    @Test fun thresholdIsConfigurableInDp() {
        assertEquals(24, MusicGesturePolicy.thresholdPx(density = 1f, dp = 24))
        assertEquals(0 + 72, MusicGesturePolicy.thresholdPx(density = 2f, dp = 36))
    }

    // ---- классификация ----

    @Test fun tapAndShortDragAreNotSwipes() {
        val t = MusicGesturePolicy.thresholdPx(density = 3f) // 144 px
        assertEquals(MusicGesturePolicy.Swipe.NONE, MusicGesturePolicy.classify(0, 0, t))
        assertEquals(MusicGesturePolicy.Swipe.NONE, MusicGesturePolicy.classify(20, -30, t))
        assertEquals(MusicGesturePolicy.Swipe.NONE, MusicGesturePolicy.classify(-t + 1, 0, t))
        assertEquals(MusicGesturePolicy.Swipe.NONE, MusicGesturePolicy.classify(0, t - 1, t))
    }

    @Test fun exactlyAtThresholdAlreadyCounts() {
        val t = 100
        assertEquals(MusicGesturePolicy.Swipe.LEFT, MusicGesturePolicy.classify(-100, 0, t))
        assertEquals(MusicGesturePolicy.Swipe.RIGHT, MusicGesturePolicy.classify(100, 0, t))
        assertEquals(MusicGesturePolicy.Swipe.UP, MusicGesturePolicy.classify(0, -100, t))
        assertEquals(MusicGesturePolicy.Swipe.DOWN, MusicGesturePolicy.classify(0, 100, t))
    }

    @Test fun dominantAxisDecidesTheDirection() {
        val t = 100
        // Явный горизонтальный жест с небольшим вертикальным дрейфом — всё ещё влево.
        assertEquals(MusicGesturePolicy.Swipe.LEFT, MusicGesturePolicy.classify(-300, 40, t))
        assertEquals(MusicGesturePolicy.Swipe.UP, MusicGesturePolicy.classify(40, -300, t))
    }

    @Test fun diagonalGestureIsNotGuessed() {
        val t = 100
        // Наискосок: ни одна ось не доминирует — команды нет (иначе трек улетал бы
        // при попытке закрыть плеер и наоборот).
        assertEquals(MusicGesturePolicy.Swipe.NONE, MusicGesturePolicy.classify(200, 200, t))
        assertEquals(MusicGesturePolicy.Swipe.NONE, MusicGesturePolicy.classify(-200, 210, t))
    }

    @Test fun zeroOrNegativeThresholdStillNeedsMovement() {
        // Защита от деления/сравнения с нулём: порог поднимается до 1 px.
        assertEquals(MusicGesturePolicy.Swipe.NONE, MusicGesturePolicy.classify(0, 0, 0))
        assertEquals(MusicGesturePolicy.Swipe.LEFT, MusicGesturePolicy.classify(-2, 0, -50))
    }

    // ---- смысл жеста на поверхностях ----

    @Test fun playerBarOpensOnlyOnSwipeUp() {
        assertEquals(
            MusicGesturePolicy.Direction.OPEN,
            MusicGesturePolicy.barSwipe(MusicGesturePolicy.Swipe.UP),
        )
        listOf(
            MusicGesturePolicy.Swipe.DOWN,
            MusicGesturePolicy.Swipe.LEFT,
            MusicGesturePolicy.Swipe.RIGHT,
            MusicGesturePolicy.Swipe.NONE,
        ).forEach { swipe ->
            assertNull("полоса плеера не должна реагировать на $swipe", MusicGesturePolicy.barSwipe(swipe))
        }
    }

    @Test fun fullPlayerClosesOnSwipeDown() {
        assertEquals(
            MusicGesturePolicy.Direction.CLOSE,
            MusicGesturePolicy.fullPlayerSwipe(MusicGesturePolicy.Swipe.DOWN),
        )
    }

    @Test fun fullPlayerSwipesTracksHorizontally() {
        assertEquals(
            MusicGesturePolicy.Direction.NEXT,
            MusicGesturePolicy.fullPlayerSwipe(MusicGesturePolicy.Swipe.LEFT),
        )
        assertEquals(
            MusicGesturePolicy.Direction.PREVIOUS,
            MusicGesturePolicy.fullPlayerSwipe(MusicGesturePolicy.Swipe.RIGHT),
        )
    }

    @Test fun fullPlayerIgnoresUpAndTaps() {
        assertNull(MusicGesturePolicy.fullPlayerSwipe(MusicGesturePolicy.Swipe.UP))
        assertNull(MusicGesturePolicy.fullPlayerSwipe(MusicGesturePolicy.Swipe.NONE))
    }

    // ---- зоны контролов ----

    @Test fun touchOnControlIsNotAGesture() {
        val seek = MusicGesturePolicy.Zone(left = 40, top = 300, right = 700, bottom = 360)
        assertTrue(MusicGesturePolicy.startsOnControl(100, 320, listOf(seek)))
        assertTrue(
            "левая граница включена",
            MusicGesturePolicy.startsOnControl(40, 300, listOf(seek)),
        )
        assertFalse(
            "правая граница исключена — соседний контрол не должен перекрываться",
            MusicGesturePolicy.startsOnControl(700, 320, listOf(seek)),
        )
        assertFalse(MusicGesturePolicy.startsOnControl(100, 361, listOf(seek)))
    }

    @Test fun emptyZoneListMeansFreeGesture() {
        assertFalse(MusicGesturePolicy.startsOnControl(10, 10, emptyList()))
    }

    @Test fun degenerateZoneNeverBlocksTheSurface() {
        // Неразмеченный/ещё не отрисованный контрол (нулевая высота) не должен съедать жест.
        val notLaidOut = MusicGesturePolicy.Zone(left = 0, top = 0, right = 0, bottom = 0)
        assertTrue(notLaidOut.isEmpty)
        assertFalse(MusicGesturePolicy.startsOnControl(0, 0, listOf(notLaidOut)))
    }
}
