package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ModelInfo

/**
 * Политика пикера параметров хода: строки (что показываем и что считаем текущим),
 * начальный фокус и D-pad-шаг. Экран рисует ровно то, что вернула политика.
 */
class TvTurnParamsPolicyTest {

    private fun entries(vararg ids: String): List<TvModelPickerEntry> = ids.map {
        TvModelPickerEntry(ModelInfo(id = it, name = it), "label:$it")
    }

    // --- строки пикера моделей ------------------------------------------------------------------

    @Test
    fun `model rows keep server order and mark the effective choice`() {
        val rows = TvTurnParamsPolicy.modelRows(entries("a", "b", "c"), currentModelId = "b")

        assertEquals(listOf("label:a", "label:b", "label:c"), rows.map { it.label })
        assertEquals(listOf(false, true, false), rows.map { it.current })
    }

    @Test
    fun `no current model - no row is marked`() {
        val rows = TvTurnParamsPolicy.modelRows(entries("a", "b"), currentModelId = null)

        assertTrue(rows.none { it.current })
        assertTrue(TvTurnParamsPolicy.modelRows(entries("a"), currentModelId = "  ").none { it.current })
    }

    @Test
    fun `empty catalog yields no rows`() {
        assertEquals(emptyList<TvTurnParamsPolicy.PickerRow>(), TvTurnParamsPolicy.modelRows(emptyList(), "a"))
    }

    // --- строки пикера уровней размышления -------------------------------------------------------

    @Test
    fun `reasoning rows mark the effective level, auto included`() {
        val options = listOf(
            TvReasoningOption(null, "Авто"),
            TvReasoningOption("low", "Low"),
            TvReasoningOption("high", "High"),
        )

        val auto = TvTurnParamsPolicy.reasoningRows(options, effectiveEffort = null)
        assertEquals(listOf(true, false, false), auto.map { it.current })

        val high = TvTurnParamsPolicy.reasoningRows(options, effectiveEffort = "high")
        assertEquals(listOf(false, false, true), high.map { it.current })
    }

    @Test
    fun `effective level outside the ladder marks nothing`() {
        val options = listOf(TvReasoningOption(null, "Авто"), TvReasoningOption("low", "Low"))

        assertFalse(TvTurnParamsPolicy.reasoningRows(options, "xhigh").any { it.current })
    }

    // --- начальный фокус -------------------------------------------------------------------------

    @Test
    fun `initial focus lands on the current row, else the first`() {
        val current = listOf(
            TvTurnParamsPolicy.PickerRow("a", current = false),
            TvTurnParamsPolicy.PickerRow("b", current = true),
        )
        assertEquals(1, TvTurnParamsPolicy.initialSelection(current))

        val none = listOf(TvTurnParamsPolicy.PickerRow("a", current = false))
        assertEquals(0, TvTurnParamsPolicy.initialSelection(none))
    }

    @Test
    fun `initial focus of an empty list is NO_SELECTION`() {
        assertEquals(TvTurnParamsPolicy.NO_SELECTION, TvTurnParamsPolicy.initialSelection(emptyList()))
    }

    // --- D-pad-шаг -------------------------------------------------------------------------------

    @Test
    fun `move steps within bounds and clamps at the edges without wrapping`() {
        assertEquals(1, TvTurnParamsPolicy.move(0, 3, delta = 1))
        assertEquals(0, TvTurnParamsPolicy.move(1, 3, delta = -1))
        assertEquals(2, TvTurnParamsPolicy.move(0, 3, delta = 2)) // зажатие на краю
        assertEquals(2, TvTurnParamsPolicy.move(2, 3, delta = 1))
        assertEquals(0, TvTurnParamsPolicy.move(0, 3, delta = -1))
    }

    @Test
    fun `move from an out-of-range selection recovers toward the direction`() {
        assertEquals(0, TvTurnParamsPolicy.move(TvTurnParamsPolicy.NO_SELECTION, 3, delta = 1))
        assertEquals(2, TvTurnParamsPolicy.move(TvTurnParamsPolicy.NO_SELECTION, 3, delta = -1))
        assertEquals(TvTurnParamsPolicy.NO_SELECTION, TvTurnParamsPolicy.move(0, 0, delta = 1))
    }
}
