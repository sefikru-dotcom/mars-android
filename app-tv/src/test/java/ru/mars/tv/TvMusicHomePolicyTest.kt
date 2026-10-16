package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack

/**
 * JVM-тесты чистой политики персональной ленты «Для тебя» ([TvMusicHomePolicy]) — без
 * Android-типов, поэтому проверяются и решения, и данные:
 *  - какие полки вообще рендерятся (пустая полка не показывается);
 *  - плоская очередь воспроизведения совпадает с порядком строк на экране;
 *  - «лента пуста» — только когда НИ ОДНА полка не несёт треков;
 *  - ответ шва [TvMusicHomeResult] превращается в состояние экрана [TvMusicHomeState];
 *  - пустая лента откатывает на чарты, сбой — нет; «Повторить» повторяет активную ветку.
 */
class TvMusicHomePolicyTest {

    private fun track(id: String) = MusicTrack(id = id, title = "Track $id", artist = "Artist $id", duration = "3:20")

    private fun shelf(id: String, vararg items: String) =
        MusicShelf(id = id, title = "Shelf $id", items = items.map(::track))

    // --- видимые полки ---------------------------------------------------------------------------

    @Test
    fun `empty shelves are dropped and the rest keep their server order`() {
        val empty = shelf("similar")
        val first = shelf("continue", "a", "b")
        val second = shelf("new", "c")
        assertEquals(
            listOf(first, second),
            TvMusicHomePolicy.visibleShelves(listOf(empty, first, second)),
        )
    }

    @Test
    fun `a shelf with no tracks never reaches the renderer`() {
        assertTrue(TvMusicHomePolicy.visibleShelves(listOf(shelf("new"))).isEmpty())
        assertTrue(TvMusicHomePolicy.visibleShelves(emptyList()).isEmpty())
    }

    // --- очередь воспроизведения -----------------------------------------------------------------

    @Test
    fun `playback queue flattens visible shelves in render order`() {
        val shelves = listOf(
            shelf("continue", "a", "b"),
            shelf("similar"), // пустая полка не попадает в очередь
            shelf("new", "c"),
        )
        assertEquals(listOf(track("a"), track("b"), track("c")), TvMusicHomePolicy.playbackQueue(shelves))
    }

    @Test
    fun `queue is empty when no shelf carries tracks`() {
        assertTrue(TvMusicHomePolicy.playbackQueue(listOf(shelf("continue"), shelf("new"))).isEmpty())
    }

    // --- пустая лента ----------------------------------------------------------------------------

    @Test
    fun `feed is empty only when not a single shelf carries tracks`() {
        assertTrue(TvMusicHomePolicy.isFeedEmpty(emptyList()))
        assertTrue(TvMusicHomePolicy.isFeedEmpty(listOf(shelf("continue"), shelf("new"))))
        assertFalse(TvMusicHomePolicy.isFeedEmpty(listOf(shelf("similar"), shelf("new", "x"))))
    }

    // --- ответ шва как состояние экрана ----------------------------------------------------------

    @Test
    fun `loaded shelves become a renderable Shelves state`() {
        val shelves = listOf(shelf("new", "x"))
        assertEquals(TvMusicHomeState.Shelves(shelves), TvMusicHomePolicy.state(TvMusicHomeResult.Loaded(shelves)))
    }

    @Test
    fun `loaded feed with nothing renderable becomes Empty`() {
        assertEquals(
            TvMusicHomeState.Empty,
            TvMusicHomePolicy.state(TvMusicHomeResult.Loaded(listOf(shelf("continue")))),
        )
        assertEquals(TvMusicHomeState.Empty, TvMusicHomePolicy.state(TvMusicHomeResult.Loaded(emptyList())))
    }

    @Test
    fun `failure keeps the message and a blank one falls back to the default`() {
        assertEquals(
            TvMusicHomeState.Failed("timeout"),
            TvMusicHomePolicy.state(TvMusicHomeResult.Failed("timeout")),
        )
        assertEquals(
            TvMusicHomeState.Failed(TvMusicScreenController.LOAD_FAILURE),
            TvMusicHomePolicy.state(TvMusicHomeResult.Failed("   ")),
        )
    }

    // --- выбор вкладки ---------------------------------------------------------------------------

    @Test
    fun `an empty feed rolls the screen back to the charts`() {
        assertEquals(
            TvMusicTab.CHARTS,
            TvMusicHomePolicy.tabAfterHomeResult(TvMusicHomeResult.Loaded(listOf(shelf("new")))),
        )
        assertEquals(
            TvMusicTab.CHARTS,
            TvMusicHomePolicy.tabAfterHomeResult(TvMusicHomeResult.Loaded(emptyList())),
        )
    }

    @Test
    fun `loaded shelves and failures both stay on the feed`() {
        assertEquals(
            TvMusicTab.FOR_YOU,
            TvMusicHomePolicy.tabAfterHomeResult(TvMusicHomeResult.Loaded(listOf(shelf("new", "x")))),
        )
        assertEquals(
            TvMusicTab.FOR_YOU,
            TvMusicHomePolicy.tabAfterHomeResult(TvMusicHomeResult.Failed("down")),
        )
    }

    @Test
    fun `retry repeats the feed only on the feed tab`() {
        assertTrue(TvMusicHomePolicy.retryRepeatsFeed(TvMusicTab.FOR_YOU))
        assertFalse(TvMusicHomePolicy.retryRepeatsFeed(TvMusicTab.CHARTS))
    }

    @Test
    fun `the feed is the startup branch and the charts is the catalog branch`() {
        assertEquals(TvMusicTab.FOR_YOU, TvMusicHomePolicy.initialTab)
        assertEquals(TvMusicTab.CHARTS, TvMusicHomePolicy.chartsTab)
    }
}
