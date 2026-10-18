package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Инкрементальная перезагрузка истории ТВ-ленты: план обязан переиспользовать
 * неизменившиеся пузыри (иначе перезагрузка снова сносит ленту целиком → ANR),
 * но при этом не терять и не дублировать строки.
 */
class TvHistoryReloadPolicyTest {

    private fun row(text: String) = "row:$text"

    @Test
    fun `two empty snapshots change nothing`() {
        assertEquals(TvHistoryReloadPlan.NoChange, TvHistoryReloadPolicy.plan(emptyList<String>(), emptyList()))
    }

    @Test
    fun `identical snapshots change nothing`() {
        val rows = listOf(row("a"), row("b"))
        assertEquals(TvHistoryReloadPlan.NoChange, TvHistoryReloadPolicy.plan(rows, rows.toList()))
    }

    @Test
    fun `an empty transcript appends from zero instead of rebuilding`() {
        assertEquals(
            TvHistoryReloadPlan.Append(0),
            TvHistoryReloadPolicy.plan(emptyList<String>(), listOf(row("a"))),
        )
    }

    @Test
    fun `a cleared transcript asks for a full rebuild`() {
        assertEquals(
            TvHistoryReloadPlan.FullRebuild,
            TvHistoryReloadPolicy.plan(listOf(row("a")), emptyList<String>()),
        )
    }

    @Test
    fun `a grown history is an append of the tail only - the reload ANR case`() {
        val previous = listOf(row("a"), row("b"), row("c"))
        val next = previous + listOf(row("d"), row("e"))
        assertEquals(TvHistoryReloadPlan.Append(3), TvHistoryReloadPolicy.plan(previous, next))
    }

    @Test
    fun `a changed last bubble patches just that bubble`() {
        val previous = listOf(row("a"), row("b"), row("c"))
        val next = listOf(row("a"), row("b"), row("c2"))
        assertEquals(
            TvHistoryReloadPlan.Patch(keepPrefix = 2, keepSuffix = 0, rebuildFrom = 2, rebuildTo = 3),
            TvHistoryReloadPolicy.plan(previous, next),
        )
    }

    @Test
    fun `a changed middle keeps both the prefix and the suffix`() {
        val previous = listOf(row("a"), row("b"), row("c"), row("d"))
        val next = listOf(row("a"), row("x"), row("c"), row("d"))
        assertEquals(
            TvHistoryReloadPlan.Patch(keepPrefix = 1, keepSuffix = 2, rebuildFrom = 1, rebuildTo = 2),
            TvHistoryReloadPolicy.plan(previous, next),
        )
    }

    @Test
    fun `a shrunk history rebuilds only the vanished tail`() {
        val previous = listOf(row("a"), row("b"), row("c"))
        val next = listOf(row("a"), row("b"))
        assertEquals(
            TvHistoryReloadPlan.Patch(keepPrefix = 2, keepSuffix = 0, rebuildFrom = 2, rebuildTo = 2),
            TvHistoryReloadPolicy.plan(previous, next),
        )
    }

    @Test
    fun `a completely different history falls back to a full rebuild`() {
        val previous = listOf(row("a"), row("b"))
        val next = listOf(row("x"), row("y"))
        assertEquals(TvHistoryReloadPlan.FullRebuild, TvHistoryReloadPolicy.plan(previous, next))
    }

    @Test
    fun `the plan never rebuilds rows that are reused as prefix or suffix`() {
        // Инвариант: rebuildFrom == keepPrefix, rebuildTo == next.size - keepSuffix,
        // то есть пересборка не задевает переиспользованные View.
        val previous = listOf(row("a"), row("b"), row("c"), row("d"), row("e"))
        val next = listOf(row("a"), row("b"), row("x"), row("d"), row("e"), row("f"))
        val plan = TvHistoryReloadPolicy.plan(previous, next) as TvHistoryReloadPlan.Patch
        assertEquals(plan.keepPrefix, plan.rebuildFrom)
        assertEquals(next.size - plan.keepSuffix, plan.rebuildTo)
        assertTrue(plan.rebuildTo > plan.rebuildFrom)
        // Строки вне диапазона пересборки действительно совпадают.
        for (index in 0 until plan.keepPrefix) assertEquals(previous[index], next[index])
        for (offset in 1..plan.keepSuffix) {
            assertEquals(previous[previous.size - offset], next[next.size - offset])
        }
    }

    @Test
    fun `a long history with one new turn reuses everything already rendered`() {
        val previous = (1..200).map { row("m$it") }
        val next = previous + row("m201")
        val plan = TvHistoryReloadPolicy.plan(previous, next)
        assertEquals(TvHistoryReloadPlan.Append(200), plan)
    }
}
