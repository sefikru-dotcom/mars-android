package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.LyricsResult

/**
 * Unit-тесты [TvLyricsViewPolicy] на РЕАЛЬНЫХ данных живого ответа
 * `GET /music/api/lyrics/<videoId>?title=…&artist=…` (фиксация 13.10.2026,
 * fixtures/lyrics_kino.json: Кино — Группа крови, плоский объект
 * `{"lyrics": "…", "synced": null, "source": "youtube"}`), плюс серверная
 * НЕПУСТАЯ заглушка «Текст не найден.» — её надо распознавать как Missing.
 */
class TvLyricsViewPolicyTest {

    /** Дословное начало тела из fixtures/lyrics_kino.json (Кино — Группа крови). */
    private val liveKinoLyrics =
        "Теплое место, но улицы ждут отпечатков наших ног.\n" +
            "Звездная пыль - на сапогах.\n" +
            "Мягкое кресло, клетчатый плед, не нажатый вовремя курок.\n" +
            "Солнечный день - в ослепительных снах.\n" +
            "\n" +
            "Группа крови - на рукаве,"

    /** Реальная форма живого ответа: плоский объект, synced = null, source = youtube. */
    private val liveKinoResult = LyricsResult(lyrics = liveKinoLyrics, synced = null, source = "youtube")

    @Test
    fun `live kino answer becomes plain Text with youtube source`() {
        val state = TvLyricsViewPolicy.resolve(liveKinoResult)
        assertTrue(state is TvLyricsUiState.Text)
        state as TvLyricsUiState.Text
        assertEquals(liveKinoLyrics, state.body)
        assertFalse(state.synced)
        assertEquals("youtube", state.source)
    }

    @Test
    fun `server not-found stub in a non-empty body is Missing not Text`() {
        // Живой сервер без title/artist часто отвечает ИМЕННО ТАК — тело непустое.
        assertEquals(TvLyricsUiState.Missing, TvLyricsViewPolicy.resolve(
            LyricsResult(lyrics = "Текст не найден.", synced = null, source = "youtube"),
        ))
        assertEquals(TvLyricsUiState.Missing, TvLyricsViewPolicy.resolve(
            LyricsResult(lyrics = "Текст не найден", synced = null, source = null),
        ))
        assertEquals(TvLyricsUiState.Missing, TvLyricsViewPolicy.resolve(
            LyricsResult(lyrics = "текст НЕ НАЙДЕН. ", synced = null, source = null),
        ))
        // Настоящий текст, начинающийся похоже, терять нельзя.
        assertTrue(
            TvLyricsViewPolicy.resolve(
                LyricsResult(lyrics = "Текст не найден в архиве, но вот куплет…", synced = null, source = null),
            ) is TvLyricsUiState.Text,
        )
    }

    @Test
    fun `plain lyrics win over synced`() {
        val state = TvLyricsViewPolicy.resolve(
            LyricsResult(lyrics = "Обычный текст", synced = "[00:01.00]Другой текст", source = null),
        )
        assertEquals(TvLyricsUiState.Text("Обычный текст", synced = false, source = null), state)
    }

    @Test
    fun `synced lrc loses timestamps and meta tags but keeps the synced flag`() {
        val state = TvLyricsViewPolicy.resolve(
            LyricsResult(
                lyrics = null,
                synced = "[ar:Кино]\n[ti:Группа крови]\n[00:12.34]Первая строка\n[00:18.90]Вторая  строка\n",
                source = "lrc",
            ),
        )
        assertTrue(state is TvLyricsUiState.Text)
        state as TvLyricsUiState.Text
        assertEquals("Первая строка\nВторая строка", state.body)
        assertTrue(state.synced)
        assertEquals("lrc", state.source)
    }

    @Test
    fun `empty everywhere is Missing`() {
        assertEquals(TvLyricsUiState.Missing, TvLyricsViewPolicy.resolve(null))
        assertEquals(TvLyricsUiState.Missing, TvLyricsViewPolicy.resolve(LyricsResult(null, null, null)))
        assertEquals(
            TvLyricsUiState.Missing,
            TvLyricsViewPolicy.resolve(LyricsResult("   \n  ", "", null)),
        )
    }

    @Test
    fun `normalize collapses blank runs and trims edges`() {
        assertEquals("куплет 1\n\nкуплет 2", TvLyricsViewPolicy.normalize("  куплет 1  \n\n\n\n\nкуплет 2 \n"))
    }

    @Test
    fun `meta line reports synced flag and source honestly`() {
        assertEquals("синхронизированный текст · источник: lrc", TvLyricsViewPolicy.metaLine(synced = true, source = "lrc"))
        assertEquals("источник: youtube", TvLyricsViewPolicy.metaLine(synced = false, source = "youtube"))
        assertEquals("синхронизированный текст", TvLyricsViewPolicy.metaLine(synced = true, source = null))
        assertEquals("", TvLyricsViewPolicy.metaLine(synced = false, source = " "))
    }

    @Test
    fun `placeholder texts are meaningful non-empty strings`() {
        assertTrue(TvLyricsViewPolicy.LOADING_TEXT.isNotBlank())
        assertTrue(TvLyricsViewPolicy.MISSING_TEXT.isNotBlank())
        assertTrue(TvLyricsPanelFlow.FETCH_FAILURE.isNotBlank())
        assertTrue(TvLyricsViewPolicy.isNotFoundStub("Текст не найден."))
        assertFalse(TvLyricsViewPolicy.isNotFoundStub("Группа крови - на рукаве"))
    }
}
