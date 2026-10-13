package ru.mars.mobile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Кэш результатов [MarkdownFormatter.parseBlocks]: попадание возвращает тот же
 * результат без повторного парсинга, LRU реально вытесняет, кэш потокобезопасен
 * (парсинг планируется и на фоновом потоке).
 */
class MarkdownFormatterCacheTest {

    @After
    fun tearDown() {
        MarkdownFormatter.clearParseCache()
    }

    private val mixedRaw = "Привет!\n```kotlin\nval a = 1\n```\n- пункт с **bold**\n> цитата\n| a | b |\n|---|---|\n| 1 | 2 |"

    @Test
    fun sameInputReturnsIdenticalCachedInstance() {
        val first = MarkdownFormatter.parseBlocks(mixedRaw)
        val second = MarkdownFormatter.parseBlocks(mixedRaw)
        assertSame("повторный parseBlocks обязан отдать закэшированный список", first, second)
        assertEquals(MarkdownFormatter.parseBlocksUncached(mixedRaw), first)
    }

    @Test
    fun differentInputsProduceDifferentResults() {
        val a = MarkdownFormatter.parseBlocks("# один заголовок")
        val b = MarkdownFormatter.parseBlocks("# другой заголовок")
        assertNotSame(a, b)
        assertNotEquals(a, b)
        assertEquals(MarkdownFormatter.parseBlocksUncached("# один заголовок"), a)
        assertEquals(MarkdownFormatter.parseBlocksUncached("# другой заголовок"), b)
    }

    @Test
    fun cacheIsBoundedAndEvictsLeastRecentlyUsedEntries() {
        val max = MarkdownFormatter.PARSE_CACHE_MAX_ENTRIES
        val eldest = "eldest message with **bold**"
        val eldestParsed = MarkdownFormatter.parseBlocks(eldest)
        assertEquals(1, MarkdownFormatter.parseCacheSize())

        // Заполняем кэш сверх ёмкости; eldest больше не трогаем — он LRU.
        for (i in 1..max) {
            MarkdownFormatter.parseBlocks("filler message $i plain text")
        }
        assertEquals(max, MarkdownFormatter.parseCacheSize())

        val reparsed = MarkdownFormatter.parseBlocks(eldest)
        assertNotSame("вытесненная запись обязана парситься заново", eldestParsed, reparsed)
        assertEquals("содержимое после повторного парсинга идентично", eldestParsed, reparsed)
    }

    @Test
    fun recentlyUsedEntrySurvivesEvictionPressure() {
        val max = MarkdownFormatter.PARSE_CACHE_MAX_ENTRIES
        val hot = "hot **entry** that stays"
        val hotParsed = MarkdownFormatter.parseBlocks(hot)
        repeat(3) { round ->
            for (i in 0 until max - 1) {
                MarkdownFormatter.parseBlocks("pressure $round $i")
                MarkdownFormatter.parseBlocks(hot) // touch — hot всегда новейший
            }
        }
        assertSame(hotParsed, MarkdownFormatter.parseBlocks(hot))
    }

    @Test
    fun oversizedInputIsParsedButNotCached() {
        val huge = "plain word ".repeat(MarkdownFormatter.PARSE_CACHE_MAX_LENGTH / 8) + "tail"
        assertTrue(huge.length > MarkdownFormatter.PARSE_CACHE_MAX_LENGTH)
        MarkdownFormatter.clearParseCache()
        val a = MarkdownFormatter.parseBlocks(huge)
        assertEquals("огромный текст не должен попадать в кэш", 0, MarkdownFormatter.parseCacheSize())
        assertNotSame(a, MarkdownFormatter.parseBlocks(huge))
        assertEquals(MarkdownFormatter.parseBlocksUncached(huge), a)
    }

    @Test
    fun emptyInputIsNotCachedAndMatchesLegacy() {
        assertEquals(MarkdownFormatter.parseBlocksUncached(""), MarkdownFormatter.parseBlocks(""))
        assertEquals(0, MarkdownFormatter.parseCacheSize())
    }

    @Test
    fun cachedResultEqualsUncachedOnMixedCorpus() {
        val corpus = listOf(
            mixedRaw,
            "just plain streaming text without markers",
            "# Заголовок\n\nТекст с *курсивом* и `кодом`.\n1. первый\n2. второй",
            "- [ ] задача\n- [x] готово\n___\n> цитата со [ссылкой](https://example.com)",
            "![картинка](https://example.com/i.png)\n~~зачёркнуто~~",
            "строка\r\nCRLF и 3. не список (нет пробела после точки? нет: есть \"3. \")\n3. список",
            "```\nunclosed fence tail",
        )
        for (raw in corpus) {
            MarkdownFormatter.clearParseCache()
            val expected = MarkdownFormatter.parseBlocksUncached(raw)
            val first = MarkdownFormatter.parseBlocks(raw)
            assertEquals("fast/медленный путь расходятся для: ${raw.take(40)}", expected, first)
            assertSame("результат обязан кэшироваться", first, MarkdownFormatter.parseBlocks(raw))
        }
    }

    @Test
    fun concurrentParsingFromMultipleThreadsIsConsistent() {
        val corpus = (0 until 40).map { i ->
            if (i % 4 == 0) "msg $i with **bold** and `code`" else "plain streaming message number $i"
        }
        val baseline = corpus.map { MarkdownFormatter.parseBlocksUncached(it) }
        MarkdownFormatter.clearParseCache()

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val failures = pool.invokeAll(
                List(threads) { t ->
                    Callable<String?> {
                        for (k in 0 until 500) {
                            val idx = (t * 500 + k) % corpus.size
                            val parsed = MarkdownFormatter.parseBlocks(corpus[idx])
                            if (parsed != baseline[idx]) return@Callable "mismatch corpus[$idx] on thread $t"
                        }
                        null
                    }
                },
                60, TimeUnit.SECONDS,
            ).map { it.get() }.filterNotNull()
            assertTrue("конкурентный парсинг разошёлся: $failures", failures.isEmpty())
        } finally {
            pool.shutdownNow()
        }
        assertTrue(MarkdownFormatter.parseCacheSize() <= MarkdownFormatter.PARSE_CACHE_MAX_ENTRIES)
    }
}
