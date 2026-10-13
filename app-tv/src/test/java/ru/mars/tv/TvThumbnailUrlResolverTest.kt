package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit-тесты [TvThumbnailUrlResolver] на РЕАЛЬНЫХ строках живого ответа чартов
 * (фиксация 13.10.2026, fixtures/charts.json):
 * `{"id":"vSA7yMrTETg", "thumbnail":"/music/api/thumb?v=vSA7yMrTETg", …}`.
 *
 * Главная грабля: thumbnail УЖЕ содержит префикс `/music`, поэтому резолвить надо
 * от origin `https://marscore.ru`, а не от baseUrl API `https://marscore.ru/music` —
 * иначе двойной `/music/music/api/thumb` и пустые обложки.
 */
class TvThumbnailUrlResolverTest {

    /** Реальная строка thumbnail из живого ответа GET /music/api/charts. */
    private val liveThumbnail = "/music/api/thumb?v=vSA7yMrTETg"

    @Test
    fun `live charts thumbnail resolves from site origin without doubled music prefix`() {
        val url = TvThumbnailUrlResolver.resolve(liveThumbnail)
        assertEquals("https://marscore.ru/music/api/thumb?v=vSA7yMrTETg", url)
        // Двойной префикс — та самая грабля резолва от baseUrl музыкального API.
        assertEquals(1, url?.split("/music/")?.size?.minus(1) ?: 0)
    }

    @Test
    fun `another live charts thumbnail row keeps its video id`() {
        assertEquals(
            "https://marscore.ru/music/api/thumb?v=J7NFL-eOxiQ",
            TvThumbnailUrlResolver.resolve("/music/api/thumb?v=J7NFL-eOxiQ"),
        )
    }

    @Test
    fun `null blank or whitespace-only resolves to null`() {
        assertNull(TvThumbnailUrlResolver.resolve(null))
        assertNull(TvThumbnailUrlResolver.resolve(""))
        assertNull(TvThumbnailUrlResolver.resolve("   "))
    }

    @Test
    fun `absolute http and https urls pass through untouched`() {
        assertEquals(
            "https://cdn.example.com/a.jpg",
            TvThumbnailUrlResolver.resolve("https://cdn.example.com/a.jpg"),
        )
        assertEquals(
            "http://cdn.example.com/a.jpg",
            TvThumbnailUrlResolver.resolve("http://cdn.example.com/a.jpg"),
        )
    }

    @Test
    fun `protocol-relative url takes the origin scheme`() {
        assertEquals(
            "https://cdn.example.com/a.jpg",
            TvThumbnailUrlResolver.resolve("//cdn.example.com/a.jpg"),
        )
    }

    @Test
    fun `relative path without leading slash is glued to origin`() {
        assertEquals(
            "https://marscore.ru/music/api/thumb?v=x",
            TvThumbnailUrlResolver.resolve("music/api/thumb?v=x"),
        )
    }

    @Test
    fun `foreign and local schemes are rejected`() {
        assertNull(TvThumbnailUrlResolver.resolve("javascript:alert(1)"))
        assertNull(TvThumbnailUrlResolver.resolve("data:image/png;base64,AAA"))
        assertNull(TvThumbnailUrlResolver.resolve("file:///sdcard/a.jpg"))
        assertNull(TvThumbnailUrlResolver.resolve("content://media/1"))
    }

    @Test
    fun `custom origin is normalized and validated`() {
        assertEquals(
            "https://example.org/music/api/thumb?v=1",
            TvThumbnailUrlResolver.resolve("/music/api/thumb?v=1", origin = "https://example.org/"),
        )
        // Невалидный origin не должен порождать мусорный URL.
        assertNull(TvThumbnailUrlResolver.resolve("/music/api/thumb?v=1", origin = "ftp://host"))
        assertNull(TvThumbnailUrlResolver.resolve("/music/api/thumb?v=1", origin = ""))
    }
}
