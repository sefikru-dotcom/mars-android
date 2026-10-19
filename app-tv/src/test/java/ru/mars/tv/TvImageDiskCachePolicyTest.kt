package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты [TvImageDiskCachePolicy] — правила дискового кэша обложек ТВ: ключ файла,
 * расширение по `Content-Type`, TTL, бюджет и порядок вытеснения.
 *
 * Политика чистая (никакого `File`/`Context`), поэтому проверяется обычными JVM-тестами:
 * на приставке нет ни эмулятора в CI, ни возможности дождаться реального TTL в 14 дней.
 */
class TvImageDiskCachePolicyTest {

    private val mb = 1024L * 1024L
    private val now = 1_760_000_000_000L // фиксированное «сейчас»: результат не зависит от часов

    private fun entry(name: String, sizeBytes: Long, lastAccessMs: Long = now) =
        TvImageDiskCacheEntry(name, sizeBytes, lastAccessMs)

    // ── ключи ────────────────────────────────────────────────────────────────────

    @Test
    fun `base key is sha256 hex and safe for the filesystem`() {
        val key = TvImageDiskCachePolicy.baseKey("https://marscore.ru/music/api/thumb?v=F9P_fIBfenk")
        assertEquals(TvImageDiskCachePolicy.KEY_LENGTH, key.length)
        assertTrue("ожидался hex: $key", key.all { it in '0'..'9' || it in 'a'..'f' })
        for (bad in listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '.')) {
            assertFalse("в ключе не должно быть $bad", key.contains(bad))
        }
    }

    @Test
    fun `base key matches sha256 of the url`() {
        // Независимая проверка: printf '%s' 'abc' | sha256sum
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TvImageDiskCachePolicy.baseKey("abc"),
        )
    }

    @Test
    fun `base key is stable and url specific`() {
        val a = TvImageDiskCachePolicy.baseKey("/music/api/thumb?v=F9P_fIBfenk")
        assertEquals(a, TvImageDiskCachePolicy.baseKey("/music/api/thumb?v=F9P_fIBfenk"))
        assertNotEquals(a, TvImageDiskCachePolicy.baseKey("/music/api/thumb?v=another"))
    }

    @Test
    fun `empty url gives a stable key instead of failing`() {
        val key = TvImageDiskCachePolicy.baseKey("")
        assertEquals(TvImageDiskCachePolicy.KEY_LENGTH, key.length)
        assertEquals(key, TvImageDiskCachePolicy.baseKey(""))
        assertNotEquals(key, TvImageDiskCachePolicy.baseKey(" "))
    }

    // ── расширение и имя файла ───────────────────────────────────────────────────

    @Test
    fun `extension follows content type`() {
        assertEquals(".jpg", TvImageDiskCachePolicy.extensionFor("image/jpeg"))
        assertEquals(".png", TvImageDiskCachePolicy.extensionFor("image/png"))
        assertEquals(".webp", TvImageDiskCachePolicy.extensionFor("image/webp"))
        assertEquals(".jpg", TvImageDiskCachePolicy.extensionFor("image/jpeg; charset=binary"))
        assertEquals(".jpg", TvImageDiskCachePolicy.extensionFor(" IMAGE/JPEG "))
    }

    @Test
    fun `unknown content type falls back to img`() {
        assertEquals(TvImageDiskCachePolicy.FALLBACK_EXTENSION, TvImageDiskCachePolicy.extensionFor(null))
        assertEquals(TvImageDiskCachePolicy.FALLBACK_EXTENSION, TvImageDiskCachePolicy.extensionFor(""))
        assertEquals(TvImageDiskCachePolicy.FALLBACK_EXTENSION, TvImageDiskCachePolicy.extensionFor("text/html"))
        assertEquals(TvImageDiskCachePolicy.FALLBACK_EXTENSION, TvImageDiskCachePolicy.extensionFor("../x/y"))
    }

    @Test
    fun `file name is the key plus extension`() {
        val url = "/music/api/thumb?v=abc"
        assertEquals(
            TvImageDiskCachePolicy.baseKey(url) + ".png",
            TvImageDiskCachePolicy.fileName(url, "image/png"),
        )
        assertEquals(
            TvImageDiskCachePolicy.baseKey(url) + TvImageDiskCachePolicy.FALLBACK_EXTENSION,
            TvImageDiskCachePolicy.fileName(url, null),
        )
    }

    @Test
    fun `entry name matches its own url only`() {
        val url = "/music/api/thumb?v=abc"
        val other = "/music/api/thumb?v=zzz"
        assertTrue(TvImageDiskCachePolicy.isEntryNameFor(TvImageDiskCachePolicy.fileName(url, "image/png"), url))
        assertFalse(TvImageDiskCachePolicy.isEntryNameFor(TvImageDiskCachePolicy.fileName(other, "image/png"), url))
    }

    @Test
    fun `entry name cannot escape the cache directory`() {
        val url = "/music/api/thumb?v=abc"
        val key = TvImageDiskCachePolicy.baseKey(url)
        assertFalse(TvImageDiskCachePolicy.isEntryNameFor(key, url))
        assertFalse(TvImageDiskCachePolicy.isEntryNameFor(key + "/../../evil", url))
        assertFalse(TvImageDiskCachePolicy.isEntryNameFor(key + "..", url))
        assertFalse(TvImageDiskCachePolicy.isEntryNameFor("../" + key + ".jpg", url))
    }

    // ── приём по размеру ─────────────────────────────────────────────────────────

    @Test
    fun `only sane positive sizes are stored`() {
        assertTrue(TvImageDiskCachePolicy.shouldStore(1))
        assertTrue(TvImageDiskCachePolicy.shouldStore(90 * 1024))
        assertTrue(TvImageDiskCachePolicy.shouldStore(TvImageDiskCachePolicy.MAX_ENTRY_BYTES))
        assertFalse(TvImageDiskCachePolicy.shouldStore(0))
        assertFalse(TvImageDiskCachePolicy.shouldStore(-1))
        assertFalse(TvImageDiskCachePolicy.shouldStore(TvImageDiskCachePolicy.MAX_ENTRY_BYTES + 1))
    }

    @Test
    fun `entry cap equals the network download cap`() {
        assertEquals(TvImageLoaderPolicy.MAX_DOWNLOAD_BYTES, TvImageDiskCachePolicy.MAX_ENTRY_BYTES)
        assertTrue(TvImageDiskCachePolicy.BUDGET_BYTES >= TvImageDiskCachePolicy.MAX_ENTRY_BYTES)
    }

    // ── TTL ──────────────────────────────────────────────────────────────────────

    @Test
    fun `fresh entry is alive`() {
        assertFalse(TvImageDiskCachePolicy.isExpired(entry("k.jpg", 1, now), now))
        assertFalse(TvImageDiskCachePolicy.isExpired(entry("k.jpg", 1, now - 60_000), now))
    }

    @Test
    fun `entry dies strictly after the ttl`() {
        assertFalse(TvImageDiskCachePolicy.isExpired(entry("k.jpg", 1, now - TvImageDiskCachePolicy.TTL_MS), now))
        assertTrue(TvImageDiskCachePolicy.isExpired(entry("k.jpg", 1, now - TvImageDiskCachePolicy.TTL_MS - 1), now))
        assertEquals(14L * 24 * 60 * 60 * 1000, TvImageDiskCachePolicy.TTL_MS)
    }

    @Test
    fun `broken timestamp is expired but future one is not`() {
        assertTrue(TvImageDiskCachePolicy.isExpired(entry("k.jpg", 1, 0), now))
        assertFalse(TvImageDiskCachePolicy.isExpired(entry("k.jpg", 1, now + 60_000), now))
    }

    // ── вытеснение ───────────────────────────────────────────────────────────────

    @Test
    fun `expired entries go first even when there is room`() {
        val entries = listOf(
            entry("stale.jpg", 10, now - TvImageDiskCachePolicy.TTL_MS - 1),
            entry("fresh.jpg", 10, now),
        )
        assertEquals(listOf("stale.jpg"), TvImageDiskCachePolicy.selectEvictions(entries, entry("new.jpg", 10, now), now))
    }

    @Test
    fun `nothing is evicted when the cache fits`() {
        val entries = listOf(entry("a.jpg", mb, now), entry("b.jpg", mb, now))
        assertTrue(TvImageDiskCachePolicy.selectEvictions(entries, entry("c.jpg", mb, now), now).isEmpty())
    }

    @Test
    fun `least recently used is evicted first`() {
        val half = TvImageDiskCachePolicy.BUDGET_BYTES / 2
        val entries = listOf(
            entry("recent.jpg", half, now),
            entry("old.jpg", half, now - 120_000),
        )
        // 16 + 16 + 16 = 48 МБ при бюджете 32 МБ — хватает одного выселения.
        assertEquals(listOf("old.jpg"), TvImageDiskCachePolicy.selectEvictions(entries, entry("new.jpg", half, now), now))
    }

    @Test
    fun `eviction keeps going until the budget fits`() {
        val quarter = TvImageDiskCachePolicy.BUDGET_BYTES / 4
        val entries = (0 until 5).map { i -> entry("e$i.jpg", quarter, now - i * 1000L) }
        val victims = TvImageDiskCachePolicy.selectEvictions(entries, entry("new.jpg", quarter, now), now)
        assertEquals(listOf("e4.jpg", "e3.jpg"), victims)
        val total = entries.filter { it.fileName !in victims }.sumOf { it.sizeBytes } + quarter
        assertTrue("бюджет после вытеснения: $total", total <= TvImageDiskCachePolicy.BUDGET_BYTES)
    }

    @Test
    fun `rewriting the same file does not evict it`() {
        val name = TvImageDiskCachePolicy.fileName("/music/api/thumb?v=abc", "image/jpeg")
        val entries = listOf(entry(name, TvImageDiskCachePolicy.BUDGET_BYTES - 1, now - 60_000))
        assertTrue(TvImageDiskCachePolicy.selectEvictions(entries, entry(name, 1, now), now).isEmpty())
    }

    @Test
    fun `entry count cap holds even when bytes are tiny`() {
        val entries = (0 until TvImageDiskCachePolicy.MAX_ENTRIES).map { i -> entry("k$i.img", 512, now - i * 1000L) }
        val victims = TvImageDiskCachePolicy.selectEvictions(entries, entry("new.img", 512, now), now)
        assertEquals(listOf("k${TvImageDiskCachePolicy.MAX_ENTRIES - 1}.img"), victims)
    }

    @Test
    fun `oversized incoming does not wipe a healthy cache`() {
        val entries = listOf(entry("a.jpg", mb, now), entry("b.jpg", mb, now))
        val huge = entry("huge.jpg", TvImageDiskCachePolicy.BUDGET_BYTES + 1, now)
        assertTrue(TvImageDiskCachePolicy.selectEvictions(entries, huge, now).isEmpty())
    }

    @Test
    fun `empty cache has nothing to evict`() {
        assertTrue(TvImageDiskCachePolicy.selectEvictions(emptyList(), entry("new.jpg", mb, now), now).isEmpty())
    }
}
