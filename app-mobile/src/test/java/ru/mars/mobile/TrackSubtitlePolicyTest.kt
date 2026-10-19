package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Раскладка подписи строки трека по РЕАЛЬНЫМ размерам, а не «на глаз».
 *
 * Числа в тестах — измеренные ширины на 12sp (`Paint.measureText` в той же вьюхе):
 * - « · 1:31» ≈ 34dp (разделитель ~10dp + четыре цифры);
 * - « · двадцать два» ≈ 79dp (разделитель + 12 символов кириллицы по ~5.7dp);
 * - доступная ширина подписи на POCO F5 (411dp): 411 − 32 (отступ корня) − 20 (паддинги
 *   строки) − 44 (обложка) − 154 (три кнопки по 34dp + отступы) − 20 (паддинги блока
 *   текста) = 141dp. Именно на этой ширине старый `maxWidth = 140dp` выдавливал
 *   длительность за край строки.
 */
class TrackSubtitlePolicyTest {

    /** Ширина сегмента « · 1:31» на 12sp. */
    private val tailDp = 34f

    /** Ширина сегмента « · двадцать два» на 12sp. */
    private val albumDp = 79f

    /** Подпись на узком телефоне (POCO F5, 411dp). */
    private val phoneDp = 141f

    @Test
    fun `narrow phone moves the album to its own line instead of squeezing the duration`() {
        assertEquals(
            TrackSubtitlePolicy.AlbumPlacement.SECOND_LINE,
            TrackSubtitlePolicy.albumPlacement(phoneDp, albumDp, tailDp),
        )
    }

    @Test
    fun `wide screen keeps the album inline`() {
        assertEquals(
            TrackSubtitlePolicy.AlbumPlacement.INLINE,
            TrackSubtitlePolicy.albumPlacement(availableDp = 320f, albumSegmentDp = albumDp, tailSegmentDp = tailDp),
        )
    }

    @Test
    fun `short album name still fits inline on a phone`() {
        // « · да» ≈ 30dp: бюджет 141 − 34 − 56 = 51dp — влезает.
        assertEquals(
            TrackSubtitlePolicy.AlbumPlacement.INLINE,
            TrackSubtitlePolicy.albumPlacement(phoneDp, albumSegmentDp = 30f, tailSegmentDp = tailDp),
        )
    }

    @Test
    fun `zero width never claims the album fits`() {
        assertEquals(
            TrackSubtitlePolicy.AlbumPlacement.SECOND_LINE,
            TrackSubtitlePolicy.albumPlacement(availableDp = 0f, albumSegmentDp = albumDp, tailSegmentDp = tailDp),
        )
    }

    /**
     * Главный инвариант брифа: длительность видна целиком, горизонтального скролла нет.
     * При INLINE сумма «альбом + хвост + минимум исполнителя» обязана влезть в ширину.
     */
    @Test
    fun `inline placement always leaves room for the whole duration and a readable artist`() {
        var inlineCases = 0
        for (width in 80..480) {
            for (album in listOf(30f, 55f, 79f, 120f, 200f)) {
                val placement = TrackSubtitlePolicy.albumPlacement(width.toFloat(), album, tailDp)
                if (placement == TrackSubtitlePolicy.AlbumPlacement.INLINE) {
                    inlineCases++
                    val needed = album + tailDp + TrackSubtitlePolicy.MIN_ARTIST_DP
                    assertTrue(
                        "INLINE при ширине ${width}dp требует ${needed}dp — длительность обрежется",
                        needed <= width.toFloat(),
                    )
                }
            }
        }
        assertTrue("сценарий без единого INLINE ничего не проверяет", inlineCases > 0)
    }

    @Test
    fun `subtitle without album reads exactly as before`() {
        assertEquals("не меняет · 1:31", TrackSubtitlePolicy.subtitle("не меняет", null, "1:31"))
        assertEquals("не меняет · 1:31", TrackSubtitlePolicy.subtitle("не меняет", "  ", "1:31"))
    }

    @Test
    fun `subtitle with album has single separators`() {
        val line = TrackSubtitlePolicy.subtitle("не меняет", "двадцать два", "1:31")
        assertEquals("не меняет · двадцать два · 1:31", line)
        assertTrue("двойной разделитель", !line.contains("·  ·"))
        assertEquals("ровно два разделителя", "··", line.replace(Regex("[^·]"), ""))
    }

    @Test
    fun `missing artist does not leave a dangling separator`() {
        assertEquals("двадцать два · 1:31", TrackSubtitlePolicy.subtitle("", "двадцать два", "1:31"))
        assertEquals("1:31", TrackSubtitlePolicy.subtitle("", null, "1:31"))
    }

    @Test
    fun `now playing status replaces the duration with the same separator rules`() {
        assertEquals(
            "не меняет · двадцать два · Сейчас играет",
            TrackSubtitlePolicy.subtitle("не меняет", "двадцать два", TrackSubtitlePolicy.NOW_PLAYING),
        )
        assertEquals(
            "не меняет · Сейчас играет",
            TrackSubtitlePolicy.subtitle("не меняет", null, TrackSubtitlePolicy.NOW_PLAYING),
        )
    }

    @Test
    fun `segments compose into the same string as the whole subtitle`() {
        val artist = "не меняет"
        val album = "двадцать два"
        val composed = artist +
            TrackSubtitlePolicy.albumSegment(artist, album) +
            TrackSubtitlePolicy.tailSegment(artist, album, "1:31")
        assertEquals(TrackSubtitlePolicy.subtitle(artist, album, "1:31"), composed)
    }

    @Test
    fun `album line is trimmed and blank means no album`() {
        assertEquals("двадцать два", TrackSubtitlePolicy.albumLine("  двадцать два "))
        assertEquals(null, TrackSubtitlePolicy.albumLine("   "))
        assertEquals(null, TrackSubtitlePolicy.albumLine(null))
    }
}
