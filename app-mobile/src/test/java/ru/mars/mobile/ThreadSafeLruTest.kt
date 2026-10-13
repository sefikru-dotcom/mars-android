package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [ThreadSafeLru]: вытеснение по LRU, освежение порядка чтением и
 * консистентность при конкурентном доступе (кэш парсинга будет дёргаться
 * и с UI-, и с фонового потока).
 */
class ThreadSafeLruTest {

    @Test
    fun evictsEldestBeyondMaxSize() {
        val lru = ThreadSafeLru<Int, String>(3)
        lru.put(1, "a")
        lru.put(2, "b")
        lru.put(3, "c")
        assertEquals(3, lru.size())
        lru.put(4, "d")
        assertEquals(3, lru.size())
        assertFalse(lru.containsKey(1))
        assertEquals(listOf(2, 3, 4), lru.keysOldestFirst())
        assertNull(lru.get(1))
        assertEquals("d", lru.get(4))
    }

    @Test
    fun getRefreshesRecency() {
        val lru = ThreadSafeLru<Int, String>(3)
        lru.put(1, "a")
        lru.put(2, "b")
        lru.put(3, "c")
        assertEquals("a", lru.get(1)) // 1 становится новейшим
        lru.put(4, "d") // вытесняется 2, а не 1
        assertTrue(lru.containsKey(1))
        assertFalse(lru.containsKey(2))
        assertEquals(listOf(3, 1, 4), lru.keysOldestFirst())
    }

    @Test
    fun putExistingKeyDoesNotGrowSize() {
        val lru = ThreadSafeLru<String, Int>(2)
        lru.put("x", 1)
        lru.put("x", 2)
        assertEquals(1, lru.size())
        assertEquals(2, lru.get("x"))
    }

    @Test
    fun clearEmptiesCache() {
        val lru = ThreadSafeLru<Int, Int>(4)
        repeat(4) { lru.put(it, it) }
        lru.clear()
        assertEquals(0, lru.size())
        assertNull(lru.get(0))
    }

    @Test
    fun rejectsNonPositiveMaxSize() {
        try {
            ThreadSafeLru<Int, Int>(0)
            fail("ожидался IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun concurrentAccessStaysConsistent() {
        val maxSize = 64
        val lru = ThreadSafeLru<Int, Int>(maxSize)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val failures = pool.invokeAll(
                List(threads) { t ->
                    Callable<String?> {
                        for (k in 0 until 5_000) {
                            val key = (t * 5_000 + k) % 512
                            lru.put(key, key * 2)
                            val got = lru.get(key)
                            // Между put и get другой поток мог вытеснить ключ —
                            // допустим null, но не чужое/битое значение.
                            if (got != null && got != key * 2) return@Callable "corrupted value for key $key: $got"
                            if (lru.size() > maxSize) return@Callable "size overflow: ${lru.size()}"
                        }
                        null
                    }
                },
                60, TimeUnit.SECONDS,
            ).map { it.get() }.filterNotNull()
            assertTrue("конкурентный доступ сломал LRU: $failures", failures.isEmpty())
        } finally {
            pool.shutdownNow()
        }
        assertTrue(lru.size() <= maxSize)
    }
}
