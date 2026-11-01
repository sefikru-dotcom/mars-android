package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Тесты [LocalModelPickPolicy] и [LocalModelPickGate]: применяется только последний выбор.
 *
 * Проверяется именно правило «чей результат применить», а не экран: экран — зона
 * координатора, а здесь — чистая логика, которая обязана отбросить поздний ответ
 * на предпоследнее нажатие.
 */
class LocalModelPickPolicyTest {

    // --- shouldApply ----------------------------------------------------------------------

    @Test
    fun `stale request is not applied`() {
        // Запрос 1 устарел, потому что последним выдали 2.
        assertFalse(LocalModelPickPolicy.shouldApply(requestSeq = 1L, latestSeq = 2L))
    }

    @Test
    fun `latest request is applied`() {
        assertTrue(LocalModelPickPolicy.shouldApply(requestSeq = 2L, latestSeq = 2L))
    }

    @Test
    fun `request ahead of latest is not applied`() {
        // Метка «из будущего» (сброс процесса, битое значение) тоже не последняя.
        assertFalse(LocalModelPickPolicy.shouldApply(requestSeq = 3L, latestSeq = 2L))
    }

    @Test
    fun `unissued sequence is never applied`() {
        // 0 — «метку не выдавали»: применять нечего, иначе первый же ответ прошёл бы
        // до любого begin().
        assertFalse(LocalModelPickPolicy.shouldApply(requestSeq = 0L, latestSeq = 0L))
        assertFalse(LocalModelPickPolicy.shouldApply(requestSeq = 0L, latestSeq = 5L))
    }

    @Test
    fun `only one of many sequences is applicable`() {
        val latest = 7L
        val applied = (1L..10L).filter { LocalModelPickPolicy.shouldApply(it, latest) }
        assertEquals(listOf(7L), applied)
    }

    // --- nextSeq --------------------------------------------------------------------------

    @Test
    fun `nextSeq grows by one`() {
        assertEquals(1L, LocalModelPickPolicy.nextSeq(0L))
        assertEquals(2L, LocalModelPickPolicy.nextSeq(1L))
        assertEquals(43L, LocalModelPickPolicy.nextSeq(42L))
    }

    @Test
    fun `nextSeq is strictly monotonic over many steps`() {
        var current = 0L
        val seen = mutableListOf<Long>()
        repeat(1_000) {
            val next = LocalModelPickPolicy.nextSeq(current)
            assertTrue("метка обязана расти: $next после $current", next > current)
            seen += next
            current = next
        }
        assertEquals(seen.distinct(), seen)
        assertEquals(1_000, seen.size)
    }

    @Test
    fun `nextSeq survives broken and saturated input`() {
        // Отрицательная метка и переполнение начинают счёт заново, но остаются
        // положительными — иначе shouldApply перестал бы применять что-либо.
        assertEquals(1L, LocalModelPickPolicy.nextSeq(-5L))
        assertEquals(1L, LocalModelPickPolicy.nextSeq(Long.MIN_VALUE))
        assertEquals(1L, LocalModelPickPolicy.nextSeq(Long.MAX_VALUE))
        assertTrue(LocalModelPickPolicy.shouldApply(1L, 1L))
    }

    // --- LocalModelPickGate ----------------------------------------------------------------

    @Test
    fun `gate applies only the last pick`() {
        val gate = LocalModelPickGate()
        val first = gate.begin()
        val second = gate.begin()
        assertTrue(first < second)
        assertFalse(gate.shouldApply(first))
        assertTrue(gate.shouldApply(second))
        assertEquals(second, gate.latestSeq)
    }

    @Test
    fun `gate reset restarts numbering`() {
        val gate = LocalModelPickGate()
        gate.begin()
        gate.begin()
        gate.reset()
        assertEquals(0L, gate.latestSeq)
        val afterReset = gate.begin()
        assertEquals(1L, afterReset)
        assertTrue(gate.shouldApply(afterReset))
    }

    @Test
    fun `concurrent begin never hands out the same sequence`() {
        val gate = LocalModelPickGate()
        val threads = 8
        val perThread = 50
        val seqs = CopyOnWriteArrayList<Long>()
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    ready.countDown()
                    start.await()
                    repeat(perThread) { seqs += gate.begin() }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, seqs.size)
        assertEquals(seqs.size, seqs.toSet().size)
        // Последняя выданная метка — единственная применимая.
        assertEquals(listOf(gate.latestSeq), seqs.filter { gate.shouldApply(it) })
    }
}
