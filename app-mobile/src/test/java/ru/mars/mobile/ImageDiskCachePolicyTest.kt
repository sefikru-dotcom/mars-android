package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты [ImageDiskCachePolicy] — единственного места, где живут правила дискового
 * кэша обложек: ключ файла, расширение по `Content-Type`, TTL, бюджет и порядок вытеснения.
 *
 * Тесты идут по фактическим контрактам загрузчика: URL обложки — это
 * `/music/api/thumb?v=F9P_fIBfenk` (относительный) и абсолютный адрес сервера музыки,
 * размеры — реального порядка (обложка 80–300 КБ, потолок загрузки 8 МБ).
 */
class ImageDiskCachePolicyTest {

    private val mb = 1024L * 1024L
    private val now = 1_760_000_000_000L // фиксированное «сейчас»: тесты не зависят от часов

    private fun entry(name: String, sizeBytes: Long, lastAccessMs: Long = now) =
        ImageDiskCacheEntry(name, sizeBytes, lastAccessMs)

    // ── ключи ────────────────────────────────────────────────────────────────────

    @Test
    fun `base key is sha256 hex and filesystem safe`() {
        val url = "https://marscore.ru/music/api/thumb?v=F9P_fIBfenk"
        val key = ImageDiskCachePolicy.baseKey(url)
        assertEquals(ImageDiskCachePolicy.KEY_LENGTH, key.length)
        assertTrue("ключ обязан быть [0-9a-f]: $key", key.all { it in '0'..'9' || it in 'a'..'f' })
        // Ни одного символа, который ФС трактует как разделитель или спецсимвол.
        for (bad in listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '.')) {
            assertFalse("ключ не должен содержать $bad", key.contains(bad))
        }
    }

    @Test
    fun `base key matches sha256 of the url`() {
        // Значение посчитано независимо: printf '%s' 'abc' | sha256sum
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ImageDiskCachePolicy.baseKey("abc"),
        )
    }

    @Test
    fun `base key is stable and distinguishes urls`() {
        val a = ImageDiskCachePolicy.baseKey("/music/api/thumb?v=F9P_fIBfenk")
        val b = ImageDiskCachePolicy.baseKey("/music/api/thumb?v=F9P_fIBfenk")
        val c = ImageDiskCachePolicy.baseKey("/music/api/thumb?v=other")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `empty url yields a stable key instead of crashing`() {
        val key = ImageDiskCachePolicy.baseKey("")
        assertEquals(ImageDiskCachePolicy.KEY_LENGTH, key.length)
        assertEquals(key, ImageDiskCachePolicy.baseKey(""))
        // Пустой URL и «похожий» пробельный — разные ключи, коллизии нет.
        assertNotEquals(key, ImageDiskCachePolicy.baseKey(" "))
    }

    // ── расширение ───────────────────────────────────────────────────────────────

    @Test
    fun `extension comes from content type`() {
        assertEquals(".jpg", ImageDiskCachePolicy.extensionFor("image/jpeg"))
        assertEquals(".png", ImageDiskCachePolicy.extensionFor("image/png"))
        assertEquals(".webp", ImageDiskCachePolicy.extensionFor("image/webp"))
    }

    @Test
    fun `content type parameters and case are ignored`() {
        assertEquals(".jpg", ImageDiskCachePolicy.extensionFor("image/jpeg; charset=binary"))
        assertEquals(".jpg", ImageDiskCachePolicy.extensionFor("  IMAGE/JPEG  "))
        assertEquals(".png", ImageDiskCachePolicy.extensionFor("Image/PNG;q=1"))
    }

    @Test
    fun `unknown or missing content type falls back to img`() {
        assertEquals(ImageDiskCachePolicy.FALLBACK_EXTENSION, ImageDiskCachePolicy.extensionFor(null))
        assertEquals(ImageDiskCachePolicy.FALLBACK_EXTENSION, ImageDiskCachePolicy.extensionFor(""))
        assertEquals(ImageDiskCachePolicy.FALLBACK_EXTENSION, ImageDiskCachePolicy.extensionFor("text/html"))
        // Заголовок — недоверенный ввод: попытка подсунуть путь не даёт расширения.
        assertEquals(ImageDiskCachePolicy.FALLBACK_EXTENSION, ImageDiskCachePolicy.extensionFor("../../etc/passwd"))
    }

    @Test
    fun `file name is key plus extension`() {
        val url = "https://marscore.ru/music/api/thumb?v=abc"
        val name = ImageDiskCachePolicy.fileName(url, "image/jpeg")
        assertEquals(ImageDiskCachePolicy.baseKey(url) + ".jpg", name)
        assertEquals(ImageDiskCachePolicy.baseKey(url) + ".img", ImageDiskCachePolicy.fileName(url, null))
    }

    // ── сопоставление имени и url ────────────────────────────────────────────────

    @Test
    fun `entry name matches its url for every known extension`() {
        val url = "/music/api/thumb?v=abc"
        assertTrue(ImageDiskCachePolicy.isEntryNameFor(ImageDiskCachePolicy.fileName(url, "image/png"), url))
        // На чтении Content-Type ещё неизвестен — подходит любое расширение белого списка.
        assertTrue(ImageDiskCachePolicy.isEntryNameFor(ImageDiskCachePolicy.baseKey(url) + ".jpg", url))
        assertTrue(ImageDiskCachePolicy.isEntryNameFor(ImageDiskCachePolicy.baseKey(url) + ".img", url))
    }

    @Test
    fun `entry name of another url does not match`() {
        val url = "/music/api/thumb?v=abc"
        val other = "/music/api/thumb?v=xyz"
        assertFalse(ImageDiskCachePolicy.isEntryNameFor(ImageDiskCachePolicy.fileName(other, "image/png"), url))
    }

    @Test
    fun `entry name cannot escape the cache directory`() {
        val url = "/music/api/thumb?v=abc"
        val key = ImageDiskCachePolicy.baseKey(url)
        // Хвост обязан быть из белого списка: ни `../`, ни пути через `/`, ни пустоты.
        assertFalse(ImageDiskCachePolicy.isEntryNameFor(key, url))
        assertFalse(ImageDiskCachePolicy.isEntryNameFor(key + "/../../evil", url))
        assertFalse(ImageDiskCachePolicy.isEntryNameFor(key + "..", url))
        assertFalse(ImageDiskCachePolicy.isEntryNameFor("../" + key + ".jpg", url))
    }

    // ── приём по размеру ─────────────────────────────────────────────────────────

    @Test
    fun `store accepted only for sane positive sizes`() {
        assertTrue(ImageDiskCachePolicy.shouldStore(1))
        assertTrue(ImageDiskCachePolicy.shouldStore(120 * 1024)) // типовая обложка
        assertTrue(ImageDiskCachePolicy.shouldStore(ImageDiskCachePolicy.MAX_ENTRY_BYTES))
        assertFalse(ImageDiskCachePolicy.shouldStore(0))
        assertFalse(ImageDiskCachePolicy.shouldStore(-1))
        assertFalse(ImageDiskCachePolicy.shouldStore(ImageDiskCachePolicy.MAX_ENTRY_BYTES + 1))
    }

    @Test
    fun `entry cap equals the network download cap`() {
        assertEquals(ImageLoaderPolicy.MAX_DOWNLOAD_BYTES, ImageDiskCachePolicy.MAX_ENTRY_BYTES)
        assertTrue(ImageDiskCachePolicy.BUDGET_BYTES >= ImageDiskCachePolicy.MAX_ENTRY_BYTES)
    }

    // ── TTL ──────────────────────────────────────────────────────────────────────

    @Test
    fun `fresh entry is not expired`() {
        assertFalse(ImageDiskCachePolicy.isExpired(entry("k.jpg", 1, now - 1000), now))
        assertFalse(ImageDiskCachePolicy.isExpired(entry("k.jpg", 1, now), now))
    }

    @Test
    fun `entry expires strictly after ttl`() {
        assertFalse(ImageDiskCachePolicy.isExpired(entry("k.jpg", 1, now - ImageDiskCachePolicy.TTL_MS), now))
        assertTrue(ImageDiskCachePolicy.isExpired(entry("k.jpg", 1, now - ImageDiskCachePolicy.TTL_MS - 1), now))
    }

    @Test
    fun `unknown timestamp counts as expired but future timestamp does not`() {
        // Битая дата файла — мусор, его надо убирать.
        assertTrue(ImageDiskCachePolicy.isExpired(entry("k.jpg", 1, 0), now))
        assertTrue(ImageDiskCachePolicy.isExpired(entry("k.jpg", 1, -5), now))
        // Сбитые часы приставки/телефона не должны стирать кэш.
        assertFalse(ImageDiskCachePolicy.isExpired(entry("k.jpg", 1, now + 60_000), now))
    }

    @Test
    fun `ttl is two weeks`() {
        assertEquals(14L * 24 * 60 * 60 * 1000, ImageDiskCachePolicy.TTL_MS)
    }

    // ── вытеснение ───────────────────────────────────────────────────────────────

    @Test
    fun `expired entries are evicted even when the budget has room`() {
        val entries = listOf(
            entry("old.jpg", 10, now - ImageDiskCachePolicy.TTL_MS - 1),
            entry("fresh.jpg", 10, now),
        )
        val victims = ImageDiskCachePolicy.selectEvictions(entries, entry("new.jpg", 10, now), now)
        assertEquals(listOf("old.jpg"), victims)
    }

    @Test
    fun `nothing is evicted while everything fits`() {
        val entries = listOf(entry("a.jpg", mb, now), entry("b.jpg", mb, now))
        assertTrue(ImageDiskCachePolicy.selectEvictions(entries, entry("c.jpg", mb, now), now).isEmpty())
    }

    @Test
    fun `over budget evicts least recently used first until it fits`() {
        val half = ImageDiskCachePolicy.BUDGET_BYTES / 2 // 16 МБ
        val entries = listOf(
            entry("recent.jpg", half, now),
            entry("stale.jpg", half, now - 60_000),
        )
        // 16 + 16 + 16 = 48 МБ при бюджете 32 МБ: одного выселения достаточно.
        val victims = ImageDiskCachePolicy.selectEvictions(entries, entry("new.jpg", half, now), now)
        assertEquals(listOf("stale.jpg"), victims)
    }

    @Test
    fun `eviction continues until the total fits`() {
        val quarter = ImageDiskCachePolicy.BUDGET_BYTES / 4 // 8 МБ
        val entries = (0 until 5).map { i -> entry("e$i.jpg", quarter, now - i * 1000L) }
        // 5 * 8 + 8 = 48 МБ при бюджете 32 МБ → выселить минимум две самые старые.
        val victims = ImageDiskCachePolicy.selectEvictions(entries, entry("new.jpg", quarter, now), now)
        assertTrue(victims.contains("e4.jpg"))
        assertTrue(victims.contains("e3.jpg"))
        val remaining = entries.filter { it.fileName !in victims }.sumOf { it.sizeBytes } + quarter
        assertTrue("после вытеснения бюджет обязан сойтись: $remaining", remaining <= ImageDiskCachePolicy.BUDGET_BYTES)
    }

    @Test
    fun `incoming entry with the same name replaces instead of duplicating`() {
        val name = ImageDiskCachePolicy.fileName("/music/api/thumb?v=abc", "image/jpeg")
        val entries = listOf(entry(name, ImageDiskCachePolicy.BUDGET_BYTES - 1, now - 60_000))
        // Перезапись того же файла: 32 МБ − 1 + 1 = ровно бюджет, вытеснять нечего.
        val victims = ImageDiskCachePolicy.selectEvictions(entries, entry(name, 1, now), now)
        assertTrue(victims.isEmpty())
    }

    @Test
    fun `entry count cap is enforced independently of the byte budget`() {
        val entries = (0 until ImageDiskCachePolicy.MAX_ENTRIES).map { i ->
            entry("k$i.img", 1024, now - i * 1000L)
        }
        val victims = ImageDiskCachePolicy.selectEvictions(entries, entry("new.img", 1024, now), now)
        assertEquals(1, victims.size)
        // Выселяется самая старая запись, а не случайная.
        assertEquals("k${ImageDiskCachePolicy.MAX_ENTRIES - 1}.img", victims.single())
    }

    @Test
    fun `incoming larger than the budget does not wipe the cache`() {
        val entries = listOf(entry("a.jpg", mb, now), entry("b.jpg", mb, now))
        val huge = entry("huge.jpg", ImageDiskCachePolicy.BUDGET_BYTES + 1, now)
        assertTrue(ImageDiskCachePolicy.selectEvictions(entries, huge, now).isEmpty())
    }

    @Test
    fun `eviction of an empty cache yields nothing`() {
        assertTrue(ImageDiskCachePolicy.selectEvictions(emptyList(), entry("new.jpg", mb, now), now).isEmpty())
    }
}
